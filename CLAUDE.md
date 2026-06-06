# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**curtli** — a URL shortener, deployed at https://curtli.com. Spring Boot 3.3 / Java 21 backend, vanilla-JS frontend (no build step), PostgreSQL source of truth, Redis for cache + rate limiting + async event stream. Hosted on AWS ECS Fargate fronted by Cloudflare.

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

### AWS deploy cycle

```bash
docker build --platform linux/amd64 -t curtli:latest .   # IMPORTANT: --platform amd64 (Fargate)
docker tag curtli:latest 818416605816.dkr.ecr.ap-south-2.amazonaws.com/curtli:latest
docker push 818416605816.dkr.ecr.ap-south-2.amazonaws.com/curtli:latest

# Bounce the running task; Fargate pulls fresh on each run-task. The Lambda
# at cf-origin-updater handles Cloudflare DNS automatically on task replacement.
aws ecs stop-task --region ap-south-2 --cluster curtli --task <task-arn>
aws ecs run-task  --region ap-south-2 --cluster curtli --task-definition curtli  \
  --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[...],securityGroups=[...],assignPublicIp=ENABLED}"
```

The `--platform linux/amd64` flag matters: without it on Apple Silicon / multi-arch builds, ECR ends up with manifest-list trees that complicate cleanup.

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

Every tunable env var must appear in **all three** of these for it to actually take effect when running Docker locally:

1. `.env.example` — the documented default
2. `docker-compose.yml` under `services.app.environment` — passes from `.env` into the container
3. `src/main/resources/application.yaml` — the property binding (with `${VAR:default}` fallback)

Forgetting #2 is the classic mistake — the value sits in `.env` but never reaches the JVM. There's a session-history of debugging this.

For AWS, the **task definition** is a fourth place. Env vars set only in `docker-compose.yml` won't reach the deployed Fargate container; secrets must come from Secrets Manager via the task def's `secrets` block.

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

## When in doubt

Read `README.md` for the architecture story. Most things have an articulated reason; don't refactor without understanding it first.
