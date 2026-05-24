# curtli

A URL shortener that **counts clicks in hourly buckets without slowing the redirect down**.

Live at **[curtli.com](https://curtli.com)**.

---

## What it does

You paste a long URL, you get back a short one. So far, nothing unusual.

The interesting part is what happens when somebody **clicks** the short link — because that's where most URL shorteners either give you no analytics, or pay for them with a slow redirect. curtli does neither.

## How click analytics work

### 1. The redirect doesn't wait for the analytics

When `GET /aB3xK9p` comes in, the controller does the minimum work needed to send a `302`:

```
GET /aB3xK9p
   │
   ├─ Resolve long URL    (Redis cache → Postgres fallback)
   ├─ Hand off the click event to an async thread pool   ← non-blocking
   └─ Return 302 Found
```

The handoff is fire-and-forget. If the analytics infrastructure is sick, the redirect still happens in single-digit milliseconds. If the async queue is full, the click is dropped on the floor — analytics is best-effort by design, never load-bearing.

### 2. Clicks go into a Redis Stream, not directly to Postgres

The async worker doesn't write to Postgres. It writes one record to a Redis Stream called `click_events`:

```
XADD click_events * shortCode=aB3xK9p ipHash=… referrer=… ts=…
```

Redis Streams act as a durable buffer with consumer-group semantics. If you scale to multiple app instances, they share work — each replica gets its own slice of the events via its `$HOSTNAME`-derived consumer name.

### 3. A scheduled consumer drains the stream in batches

Every ~1 second (configurable), a `@Scheduled` job runs on every app instance:

```
XREADGROUP curtli-group $HOSTNAME COUNT 1500 BLOCK 250ms
   │
   ▼
ClickAggregator.flush(events)
```

The aggregator does two things, and this is where the cost savings come from.

### 4. Hourly bucketing — N clicks become 1 DB write

Each event has a `shortCode` and a timestamp. The aggregator:

1. **Groups events by `shortCode`** within the batch.
2. **Truncates "now" to the hour** to get a `bucket_hour`.
3. For each `(shortCode, bucket_hour)` pair, issues a Postgres **UPSERT** against `click_stats`:

```sql
INSERT INTO click_stats (link_id, bucket_hour, click_count)
VALUES (:linkId, :bucketHour, :count)
ON CONFLICT (link_id, bucket_hour)
DO UPDATE SET click_count = click_stats.click_count + EXCLUDED.click_count
```

So 500 clicks on the same short link in the same hour produce **one** DB row — and on subsequent flushes within that hour, **one** UPDATE that increments the counter. Not 500 inserts. Not 500 row locks. Not 500 round-trips. One.

The `click_stats` table ends up looking like this:

| link_id | bucket_hour              | click_count |
|---------|--------------------------|-------------|
| 42      | 2026-05-17 14:00:00+00   | 1283        |
| 42      | 2026-05-17 15:00:00+00   | 947         |
| 42      | 2026-05-17 16:00:00+00   | 1102        |
| 99      | 2026-05-17 14:00:00+00   | 12          |

Which is exactly what you want for "clicks per hour over the last 24h" charts — no rollup query needed, no scanning a raw events table, just a range scan on `(link_id, bucket_hour DESC)`.

A separate lifetime counter on the `links` table gets the same `+= N` treatment in the same transaction, so the all-time total stays in sync.

### 5. Trade-offs

- **No sub-hour resolution.** If you need "clicks per minute," this design throws that information away. The reasoning: minute-level resolution would mean 60× more rows for a marginal product win.
- **Eventually consistent.** A click registered now shows up in the `click_stats` table on the next consumer tick — typically within 1–2 seconds, never more than a few. The `links.click_count` lifetime total has the same lag.
- **Duplicate-click debounce, not de-dup.** A 10-second Redis `SETNX` per `(shortCode, IP)` collapses retries, double-clicks, browser prefetches, and bot follow-throughs that hit within 10s of each other. It's a heuristic, not perfect — but it's a single Redis round-trip and it knocks out the obvious noise.

## What else it does

- **Batch shortening** — `POST /api/shorten` takes an array of 1–20 URLs and returns a partial-success body (`successful`/`failed` arrays) so one bad URL doesn't sink the whole batch. The frontend always sends an array, even for a single link.
- **Custom aliases** — each item can carry an optional `customAlias` (`[a-zA-Z0-9_-]{3,16}`). The unique-constraint race is handled by attempting the insert and catching the violation — no pre-check, no TOCTOU window.
- **Per-link stats** — `GET /api/links/{id}/stats` returns the 24 most recent hourly buckets plus the lifetime click count. The endpoint takes the numeric `id` (returned in `ShortenResponse` and stored client-side in localStorage), so the public `shortCode` isn't a stats key. The frontend's "Your links" section opens a modal with a bar chart when you click a saved link.
- **Expiry** — `expiresInDays` is validated `1..3650`; cache TTL is bounded by the link's own expiry so a cached entry never outlives the link itself.
- **Rate limiting** — Redis-backed token bucket implemented in a single atomic Lua script. One bucket (`10/min/IP`) covers the shorten endpoint regardless of how many URLs the request carries.
- **Random Base62 short codes** — 7 chars from `SecureRandom`, 62⁷ ≈ 3.5 trillion possible values. Codes are unguessable and the keyspace doesn't visibly leak how many links exist.

## Quick start

```bash
cp .env.example .env
docker compose up --build
```

Open **http://localhost:8080/**. Postgres, Redis, and the app come up together; Flyway runs the migrations on first boot.

## API

| Method | Path                       | Notes |
|--------|----------------------------|-------|
| `POST` | `/api/shorten`             | Takes an array of 1–20 URLs. Partial-success response. |
| `GET`  | `/api/links/{id}/stats`    | 24-hour hourly click breakdown for a link, looked up by numeric id. |
| `GET`  | `/{shortCode}`             | 302 to the long URL (or 404 / 410). |
| `GET`  | `/swagger-ui.html`         | Interactive API docs. |
| `GET`  | `/actuator/prometheus`     | Metrics. |

Request body for `POST /api/shorten`:

```json
[
  {
    "longUrl": "https://example.com/something/long",
    "customAlias": "launch-2026",
    "expiresInDays": 30
  }
]
```

`customAlias` and `expiresInDays` are optional. `expiresInDays` is `null` (or omitted) for permanent links.

Response shape (single item shown for brevity — the array is always returned in `successful` or `failed`):

```json
{
  "successful": [
    {
      "id": 42,
      "shortCode": "aB3xK9p",
      "shortUrl": "https://curtli.com/aB3xK9p",
      "longUrl": "https://example.com/something/long"
    }
  ],
  "failed": []
}
```

Hold onto `id` client-side — that's the key to `GET /api/links/{id}/stats`.

## Production deployment

Live at **[curtli.com](https://curtli.com)**, running on AWS with Cloudflare in front.

```
            ─── https ───►  Cloudflare
                            (TLS, CDN, DDoS, port 80/443 → 8080)
                                   │
                                   ▼
                          ECS Fargate task
                          (0.25 vCPU · 1 GiB · public IP · port 8080)
                              │            │
                              ▼            ▼
                       RDS Postgres   ElastiCache Redis
                       (free tier)    (free tier)
```

A few decisions worth flagging:

- **Cloudflare instead of an Application Load Balancer.** A single-task setup doesn't need L7 load balancing, and ALB is ~$16/mo plus an ACM cert dance. Cloudflare gives free TLS termination, a global CDN, DDoS protection, and translates public `:443` → origin `:8080` — at $0/mo. Total infra cost lands around **$4/mo** (the Fargate public IPv4 charge plus tiny Route 53 fees), with RDS and ElastiCache inside the free-tier window.
- **Hardened container, port 8080.** The image runs as a non-root `appuser`, which can't bind privileged ports `<1024`. Rather than re-privilege the container to bind `:80` directly, Cloudflare handles the port translation at the edge.
- **Lettuce client timeout > Redis BLOCK duration.** The consumer issues `XREADGROUP ... BLOCK 500ms`, so the client-side Lettuce timeout must exceed that or every poll gets cancelled before Redis can respond. Configured via `SPRING_DATA_REDIS_TIMEOUT=5000`.
- **MKSTREAM on startup.** `ClickEventConsumer.initGroup()` checks if the stream exists and primes it with a one-shot `XADD` before creating the consumer group — the high-level Spring Data Redis API doesn't expose Redis's `MKSTREAM` flag directly.
- **Cache-node endpoint, not Replication Group endpoint** for ElastiCache. Fargate's VPC DNS resolver was inconsistent on the `.ng.0001.aps2` Replication Group endpoint at launch; pointing at the stable specific-node endpoint (`curtli-redis-001…`) sidestepped it.

## Stack

Java 21 · Spring Boot 3.3 · PostgreSQL 16 (Flyway) · Redis 7 · Resilience4j · vanilla HTML/CSS/JS frontend · Docker Compose.
