#!/usr/bin/env bash
#
# Daily Postgres dump → S3. Designed to run via cron as root on the EC2 host.
#
# Requires:
#   - the postgres container ($CONTAINER) running with the database accessible
#   - the EC2 instance's IAM role granting s3:PutObject on the target bucket
#   - bucket-side lifecycle rule for retention (we don't delete here)
#
# BUCKET must be set in the environment (typically via /etc/cron.d/curtli).
# Install via scripts/curtli.cron.example.

set -euo pipefail

# ─── Config ──────────────────────────────────────────────────────────────────
BUCKET="${BUCKET:-}"
REGION="${REGION:-ap-south-2}"
CONTAINER="${CONTAINER:-curtli-db}"
DB_NAME="${DB_NAME:-curtli}"
DB_USER="${DB_USER:-postgres}"
LOG_TAG="curtli-backup"

# ─── Helpers ─────────────────────────────────────────────────────────────────
log() { echo "$(date -Iseconds)  $*"; logger -t "$LOG_TAG" -- "$*" 2>/dev/null || true; }
die() { log "ERROR: $*"; exit 1; }

# ─── Sanity checks ───────────────────────────────────────────────────────────
[ -n "$BUCKET" ] || die "BUCKET env var not set — see scripts/curtli.cron.example"

docker ps --format '{{.Names}}' | grep -q "^${CONTAINER}$" \
    || die "container '$CONTAINER' is not running"

# ─── Dump ───────────────────────────────────────────────────────────────────
TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
DUMP_FILE="/tmp/curtli-${TIMESTAMP}.sql.gz"
S3_KEY="dumps/${TIMESTAMP}.sql.gz"

log "dumping $DB_NAME from container $CONTAINER"

# pg_dump via docker exec — no postgresql-client install needed on the host.
# --no-owner --no-privileges keeps the dump portable across Postgres roles
# in case we ever restore into a fresh container with different credentials.
docker exec "$CONTAINER" pg_dump \
    -U "$DB_USER" -d "$DB_NAME" \
    --no-owner --no-privileges \
    | gzip -9 > "$DUMP_FILE"

SIZE=$(stat -c %s "$DUMP_FILE")
log "dump complete: ${SIZE} bytes"

# ─── Upload ─────────────────────────────────────────────────────────────────
# --sse AES256 uses S3-managed encryption (free, no KMS setup).
aws s3 cp "$DUMP_FILE" "s3://${BUCKET}/${S3_KEY}" \
    --region "$REGION" \
    --sse AES256 \
    --no-progress \
    > /dev/null

log "uploaded to s3://${BUCKET}/${S3_KEY}"

# ─── Cleanup ────────────────────────────────────────────────────────────────
rm -f "$DUMP_FILE"
log "done"
