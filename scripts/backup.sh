#!/usr/bin/env bash
#
# Weekly pg_dump of the deployed database.
#
# Free tiers die, and this one holds two things that cannot be regenerated
# identically: the seeded corpus, and the ground-truth labels the phase-8
# evaluation is scored against. Re-running the seed generator produces a
# statistically similar corpus, not the same one, so a comparison across runs
# would be measuring the corpus rather than the clustering.
#
# Reads credentials from backend/.env.seed, the same gitignored file the seed
# script uses, so nothing sensitive reaches the command line, shell history, or
# `ps` output.
#
# Usage:
#   ./scripts/backup.sh              # writes backups/civictrack-<date>.dump.gz
#   ./scripts/backup.sh /some/dir    # writes there instead
#
# Weekly, via cron (Sunday 02:00):
#   0 2 * * 0 cd /path/to/complaint-system && ./scripts/backup.sh >> backups/backup.log 2>&1
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE=backend/.env.seed
if [ ! -f "$ENV_FILE" ]; then
  echo "Missing $ENV_FILE. Copy backend/.env.seed.example to it and fill in." >&2
  exit 1
fi
set -a; . "$ENV_FILE"; set +a

if [[ "$DATABASE_URL" == *REPLACE-WITH* ]]; then
  echo "DATABASE_URL still has the placeholder host in it." >&2
  exit 1
fi

# Parse host out of the JDBC URL. The credentials stay in their own variables.
HOST=$(echo "$DATABASE_URL" | sed -E 's#^jdbc:postgresql://([^:/]+).*#\1#')
PORT=$(echo "$DATABASE_URL" | sed -E 's#^jdbc:postgresql://[^:]+:([0-9]+).*#\1#')
DB=$(echo "$DATABASE_URL" | sed -E 's#.*/([^/?]+)(\?.*)?$#\1#')

OUT_DIR="${1:-backups}"
mkdir -p "$OUT_DIR"
STAMP=$(date +%Y-%m-%d)
OUT="$OUT_DIR/civictrack-$STAMP.dump.gz"

echo "Dumping $DB from $HOST:$PORT -> $OUT"

# pg_dump runs in a container so the script does not depend on a matching
# client being installed. The image's major version must be >= the server's,
# because pg_dump refuses to dump a newer server than itself. Supabase is
# PostgreSQL 17.
#
# --schema=public is deliberate. Without it the dump also carries Supabase's
# own auth, storage and realtime schemas -- hundreds of objects this project
# does not own, cannot restore anywhere but Supabase, and has no business
# keeping a copy of. Everything CivicTrack owns lives in public.
docker run --rm -i \
  -e PGPASSWORD="$DATABASE_PASSWORD" \
  postgres:17-alpine \
  pg_dump -h "$HOST" -p "$PORT" -U "$DATABASE_USER" -d "$DB" \
          --schema=public --format=custom --no-owner --no-acl \
  | gzip > "$OUT"

# A dump that silently produced nothing is worse than no dump, because it looks
# like a backup. pg_dump's custom format starts with the magic bytes "PGDMP".
SIZE=$(wc -c < "$OUT" | tr -d ' ')
if [ "$SIZE" -lt 1024 ]; then
  echo "Dump is only ${SIZE} bytes -- almost certainly a failure. Removing it." >&2
  rm -f "$OUT"
  exit 1
fi
# Read the first five bytes without tripping over `set -o pipefail`: `head -c`
# closes the pipe as soon as it has what it needs, gzip dies of SIGPIPE, and the
# pipeline reports failure for a file that is perfectly fine. That false alarm
# deleted a good dump the first time this ran.
MAGIC=$( { gzip -dc "$OUT" 2>/dev/null || true; } | head -c 5 || true )
if [ "$MAGIC" != "PGDMP" ]; then
  echo "Dump does not begin with the PGDMP magic bytes (got '${MAGIC}');" >&2
  echo "it is not a valid custom-format dump." >&2
  rm -f "$OUT"
  exit 1
fi

echo "Wrote $OUT ($(du -h "$OUT" | cut -f1)), verified as a custom-format dump."

# Keep the last 8 weeks. Enough to notice and recover from a corruption that
# was not spotted immediately, without filling a laptop.
ls -1t "$OUT_DIR"/civictrack-*.dump.gz 2>/dev/null | tail -n +9 | while read -r old; do
  echo "Pruning $old"
  rm -f "$old"
done

cat <<'NOTE'

To restore:  ./scripts/restore.sh <dump.gz> <target-jdbc-url> <user>
To check a dump is restorable, against a throwaway container:
             ./scripts/restore.sh --self-test

Use the script. The four-line pg_restore command that used to be printed here
was wrong: `--schema=public` above does not carry the PostGIS extension -- an
extension is a database-level object, so a schema filter excludes it -- and
restoring without creating PostGIS first fails with "type public.geometry does
not exist" on every spatial table, then cascades to all 36 objects that
reference them.

The reason nobody noticed for a month is the part worth remembering:
pg_restore EXITS 0 ANYWAY. It calls those "errors ignored on restore" and
returns success, so the obvious check said the restore worked while the
database was empty. restore.sh counts rows instead of trusting the exit code.
NOTE
