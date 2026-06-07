# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**curtli** — a URL shortener, deployed at https://curtli.com. Spring Boot 3.3 / Java 21 backend, vanilla-JS frontend (no build step), PostgreSQL source of truth, Redis for cache + rate limiting + async event stream. Hosted on a single AWS EC2 t4g.small (Graviton/ARM) running `docker compose`, fronted by Cloudflare.

`README.md` carries the architecture story (click pipeline, fail-open patterns, deployment topology). Read it before refactoring — most "weird" choices have a justified reason.

## Heads-up: the directory is named `linkly`

The git working tree may live in a folder called `linkly/` and the GitHub remote may still be `sagarchy-05/linkly`. **The project itself was renamed to `curtli`** mid-development — all Java packages (`com.sagar.curtli`), config prefixes (`curtli.*`), container names (`curtli-app`, `curtli-db`, `curtli-redis`), env vars (`CURTLI_*`), and the public domain (curtli.com) use the new name. Don't reintroduce `linkly` anywhere. The legacy folder name is cosmetic and intentionally unchanged.

## Commands

### Local development (recommended path)

```bash
cp .env.example .env       # first time only
docker compose up --build  # Postgres + Redis + app, with Flyway migrations
```

App at http://localhost:8080. Hard-reload (Ctrl+Shift+R) the browser after editing static files — Spring Boot serves them with cache headers and the browser will hold stale versions.

```bash
docker compose down        # stop
docker compose down -v     # reset (drops Postgres + Redis volumes — useful when migrations change)
docker compose logs -f app # tail app logs
```

### Maven (without Docker, requires Postgres + Redis running separately)

```bash
./mvnw clean package -DskipTests       # build the jar
./mvnw test                            # run all tests (currently just context-loads)
./mvnw test -Dtest=ClassName           # one test class
./mvnw test -Dtest=ClassName#method    # one test method
./mvnw spring-boot:run                 # run locally (env vars must be set)
```

### Production deploy

The host is EC2 `i-097d9f76f778fc73a` in `ap-south-2`, running AL2023 ARM64. Access is via SSM Session Manager — port 22 is not exposed. The repo lives at `/opt/curtli`.

```bash
# Open a session on the host (from your local machine, AWS CLI installed)
aws ssm start-session --region ap-south-2 --target i-097d9f76f778fc73a

# Inside the session — pull and redeploy
cd /opt/curtli
sudo git pull
sudo docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build

# Verify
sudo docker compose ps
curl -fsS http://localhost:8080/actuator/health
```

No image registry round-trip — builds happen on the host. The t4g.small (ARM/Graviton) builds the image natively, so there's no `--platform` flag dance.

`docker-compose.prod.yml` is an **override** layered on top of the base compose. It carries:
- Memory limits per service (`app: 800m`, `postgres: 400m`, `redis: 200m`)
- `JAVA_TOOL_OPTIONS=-Xms256m -Xmx640m` (the JVM auto-reads this — works around the exec-form ENTRYPOINT that ignores `JAVA_OPTS`)
- Postgres `shared_buffers=128MB max_connections=50`
- Redis `--maxmemory 100mb --maxmemory-policy noeviction` (deliberate — see [docker-compose.prod.yml](docker-compose.prod.yml) for the per-write-path failure mode breakdown)

If you ever need to bootstrap a fresh host, `scripts/host-bootstrap.sh` is the script. It's idempotent — safe to re-run on an existing box.

## Things that LOOK like bugs but are deliberate

Read before "fixing":

### `ShortenerService.shorten()` is **not** `@Transactional` (deliberate)
Each `repository.save()` runs in its own auto-managed transaction (via `SimpleJpaRepository`). This is required for the **random-code collision retry loop** — if the method were `@Transactional`, the first `DataIntegrityViolationException` would mark the entire transaction as rollback-only, and every subsequent retry would silently fail at commit time. Also avoids populating the Redis cache before the DB commit succeeds. There's a doc comment on the method explaining this.

### Custom-alias path attempts the INSERT and catches the constraint violation (deliberate)
We don't pre-check `existsByShortCode` before INSERT. Pre-checking has a TOCTOU window where two concurrent requests both see an alias as available, both insert, one still fails the unique constraint. The "ask forgiveness" pattern eliminates the race. Adding a pre-check is a regression.

### `ClickEventConsumer.initGroup()` does a no-op XADD before `createGroup` (deliberate)
Spring Data Redis's high-level `createGroup` API doesn't expose Redis's `MKSTREAM` flag. On a fresh Redis (first deploy ever, or a wiped volume), `createGroup` fails because the stream doesn't exist yet. The fix is to prime the stream with a dummy `XADD` if `hasKey` returns false, then create the group. The consumer skips records with no `shortCode` so the dummy entry doesn't pollute analytics.

### `initGroup`'s catch walks the **cause chain** for `BUSYGROUP`, not just `e.getMessage()` (deliberate)
Spring wraps Lettuce's `RedisBusyException` in `RedisSystemException("Error in execution")`. The "BUSYGROUP" token is in the *cause's* message, not the outer one. Checking only `e.getMessage().contains("BUSYGROUP")` misclassifies every subsequent-boot's expected "group already exists" as CRITICAL. Don't simplify the helper back to a single-level check.

### `consume()` catches everything and logs WARN (deliberate)
A transient Redis hiccup or DB blip would otherwise spam ERROR + full stack trace via Spring's scheduler error handler on every tick (saw this with the original NOGROUP bug). The single-line WARN is intentional log dampening.

### `RejectedExecutionHandler` on the click publisher pool is a silent drop (deliberate)
Analytics is best-effort. Click events get dropped on async pool overload rather than blocking the redirect thread. This is the entire point of the fire-and-forget pattern.

### Cache write failures are caught and ignored (deliberate)
`cachePut` in both `ShortenerService` and `ResolverService` wraps the Redis call in a try/catch that logs and continues. Redis being down must not break the redirect path — the DB is still the source of truth. Same philosophy applies to the `ClickDebouncer` (fails open).

### `RateLimitFilter` reads `X-Forwarded-For` even though Spring Boot's `forward-headers-strategy: framework` is set
Spring's forwarded-headers handling updates `request.isSecure()` / `getRemoteAddr()` for the controller, but the rate limit filter runs early in the chain and prefers explicit, observable behavior. Don't refactor it to rely on `getRemoteAddr()` alone.

## Configuration that lives in three places

Every tunable env var must appear in **all three** of these for it to actually take effect:

1. `.env.example` — the documented default
2. `docker-compose.yml` under `services.app.environment` — passes from `.env` into the container
3. `src/main/resources/application.yaml` — the property binding (with `${VAR:default}` fallback)

Forgetting #2 is the classic mistake — the value sits in `.env` but never reaches the JVM. There's a session-history of debugging this.

In production, real values live in `/opt/curtli/.env` on the EC2 host — same shape as `.env.example`, just with actual secrets (DB password, `CURTLI_IP_HASH_SECRET`, etc.). The file is `chmod 600`-owned by root so only root can read it; `docker compose` reads it directly when invoked with sudo. Don't commit anything resembling a real value into the example file.

## Key invariants

- **`SPRING_DATA_REDIS_TIMEOUT` ≥ `STREAM_BLOCK_MILLIS`.** The consumer issues `XREADGROUP ... BLOCK 250ms` by default; if the Lettuce client timeout is shorter, every blocking poll is cancelled client-side before Redis can respond. Default values are 5000ms client timeout, 250ms block — leaves margin.
- **Random Base62 codes are 7 chars from `SecureRandom`**, not Base62-encoded sequential IDs. Sequential IDs leak total link count and are enumerable. Don't "simplify" this.
- **The `consumer-name` is `${HOSTNAME:curtli-worker-1}`** in `application.yaml` — gives each Fargate task a unique consumer name so horizontal scaling works correctly via Redis Streams' consumer groups. A static value would collapse multi-replica deployments to one active consumer.
- **`@Column(columnDefinition = "TEXT")`** is used on long-string columns in `Link.java` because `ddl-auto: validate` requires exact type match against the migration; default `String` → `VARCHAR(255)` would fail validation.

## Frontend conventions

`src/main/resources/static/` is vanilla HTML/CSS/JS — **no build step, no package.json, no node_modules**. Spring Boot's static handler serves it directly. Edit, save, hard-reload.

- **The X (trash) button on form rows is wired in `addRow()`**, not in the HTML. If `addRow` ever stops adding the click handler, the button visibly exists but does nothing — a regression we hit once.
- **Click on a saved link in "Your links"** opens the stats modal, not the URL. The copy button is for actually using the link.
- **The stats modal uses a custom-formatted `formatHour`** that includes minutes only when non-zero. This is to handle IST and other half-hour-offset timezones correctly — UTC 14:00 buckets are 19:30 IST, not 19:00.
- **localStorage history items must carry the numeric `id`** returned from the API, not just the shortCode — that's the lookup key for `/api/links/{id}/stats`.

## Database

PostgreSQL with **Flyway migrations** in `src/main/resources/db/migration/`. Schema changes go in a new `V<n>__description.sql` — never edit existing migrations. `ddl-auto: validate` is on, so a JPA entity field type mismatch against the schema fails the app at startup, not silently at query time.

`TIMESTAMPTZ` is used for time columns to keep timezone semantics deterministic; the JPA mapping is `OffsetDateTime`.

## Redis layout

| Key pattern | What it is |
|---|---|
| `link:{shortCode}` | Cache of long URL, TTL = min(global, secondsUntilExpiry) |
| `rl:{ip}` | Rate-limit token bucket (Lua-script managed) |
| `lock:click:{shortCode}:{ip}` | Click debouncer SETNX lock, 10s TTL |
| `click_events` (stream) | Click event stream, consumer group `curtli-group` |

## Production topology

```
            ─── https ───►  Cloudflare
                            (TLS, CDN, DDoS, port 443 → origin :8080)
                                   │
                                   ▼  (SG: TCP 8080 from CF IPs only)
                          EC2 t4g.small  (ap-south-2, Elastic IP)
                          AL2023 ARM, 2 vCPU / 2 GB + 2 GB swap
                          /opt/curtli — docker compose, 3 containers
                              │
                              ├─ curtli-app    (Java 21, 800m cap)
                              ├─ curtli-db     (Postgres 16, 400m cap)
                              └─ curtli-redis  (Redis 7,   200m cap)
                                   │
                                   ▼  (nightly via cron)
                              S3: curtli-db-backups-*
                              (gzipped pg_dump, 30-day lifecycle)
```

**SSM-only access.** Port 22 is closed. To get a shell: `aws ssm start-session --region ap-south-2 --target i-097d9f76f778fc73a`. The instance has an emergency key pair (`curtli-emergency.pem`) but the SG doesn't open 22 — if SSM ever breaks, temporarily add an SSH rule from your IP, fix the issue, remove the rule.

**Cron jobs** (`/etc/cron.d/curtli`):
- `02:00 UTC daily` — `scripts/pg-backup.sh` → S3 (gzipped `pg_dump`)
- `04:00 UTC Sundays` — `scripts/cloudflare-sg-sync.sh` keeps the SG's port-8080 inbound rules in sync with Cloudflare's published IP list, IPv4 + IPv6

**EC2 IAM role** (`curtli-ec2-role`):
- `AmazonSSMManagedInstanceCore` (SSM access)
- Inline `curtli-s3-backup-access` (`s3:Put/Get/Delete` on `curtli-db-backups-*/*`)
- Inline `curtli-sg-management` (`ec2:Authorize/Revoke/DescribeSecurityGroups`)

**`scripts/` overview**:
| File | What |
|---|---|
| `host-bootstrap.sh` | First-time setup of a fresh EC2 (Docker, Compose, Buildx, swap, cron, dnf-automatic) |
| `cloudflare-sg-sync.sh` | Diff-aware sync of SG port-8080 inbound against CF's IP list (idempotent, cron-safe) |
| `pg-backup.sh` | Daily Postgres dump → S3 with SSE-S3 encryption |
| `curtli.cron.example` | Template for `/etc/cron.d/curtli`; edit `BUCKET=` then `cp` to `/etc/cron.d/` |

## When in doubt

Read `README.md` for the architecture story. Most things have an articulated reason; don't refactor without understanding it first.
