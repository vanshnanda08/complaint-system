#!/usr/bin/env bash
#
# Restores a backup taken by scripts/backup.sh.
#
# WHY THIS IS A SCRIPT AND NOT A NOTE IN THE README.
#
# It used to be a four-line note at the end of backup.sh, and that note was
# wrong. It said to pipe the dump into pg_restore against an empty database,
# and doing exactly that fails with 36 errors and leaves the database empty --
# because `pg_dump --schema=public` does NOT carry the PostGIS extension. An
# extension is a database-level object, so filtering by schema excludes it, and
# every spatial table in the dump then fails to create with
#
#     ERROR: type "public.geometry" does not exist
#
# taking every table that references it down with it. The target database has
# to have PostGIS in place BEFORE the restore starts.
#
# And the reason that was never noticed: pg_restore EXITS 0 ANYWAY. It reports
# "errors ignored on restore: 36" as a warning and returns success, so the
# obvious check -- did the command succeed? -- says yes about a database with
# nothing in it. This script checks the row counts instead.
#
# Usage:
#   ./scripts/restore.sh <dump.gz> <target-jdbc-url> <user> [password-env-var]
#   ./scripts/restore.sh --self-test <dump.gz>
#
# --self-test restores into a throwaway local container and verifies the result,
# touching nothing you care about. Run it after any change to backup.sh, and run
# it periodically regardless: an untested backup is a file, not a backup.
set -euo pipefail
cd "$(dirname "$0")/.."

# Extensions the schema depends on, in the order V1__baseline.sql creates them.
EXTENSIONS="CREATE EXTENSION IF NOT EXISTS postgis; \
CREATE EXTENSION IF NOT EXISTS btree_gist; \
CREATE EXTENSION IF NOT EXISTS pgcrypto;"

# Tables that must come back non-empty for the restore to count as a restore.
EXPECTED_TABLES="issues reports wards users categories departments flyway_schema_history"

usage() { sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

# Counts every expected table in ONE query.
#
# This was a loop of one `docker exec psql` per table, and it was flaky: each
# exec is a separate round trip into an amd64 image running under emulation on
# an arm64 host, and roughly one in seven came back empty -- reporting a table
# holding 4 rows as missing. A check that fails at random is worse than no
# check, because it teaches you to disregard it. One query, one round trip, no
# race.
count_query() {
  local first=1
  for t in $EXPECTED_TABLES; do
    [ $first -eq 1 ] || printf ' UNION ALL '
    printf "SELECT '%s' AS t, count(*) AS n FROM %s" "$t" "$t"
    first=0
  done
}


# --------------------------------------------------------------------------
# Self-test: restore into a disposable container and check what came back.
# --------------------------------------------------------------------------
if [ "${1:-}" = "--self-test" ]; then
  DUMP="${2:-}"
  if [ -z "$DUMP" ]; then
    DUMP=$(ls -1t backups/civictrack-*.dump.gz 2>/dev/null | head -1 || true)
  fi
  [ -n "$DUMP" ] && [ -f "$DUMP" ] || { echo "No dump found. Pass one explicitly." >&2; exit 1; }

  CONTAINER=civictrack-restore-selftest
  echo "Self-test: restoring $DUMP into a throwaway postgis/postgis:17-3.4"
  echo "The version matters: pg_restore refuses a dump from a server newer than"
  echo "itself, and the deployed database is PostgreSQL 17."
  echo

  trap 'docker rm -f "$CONTAINER" >/dev/null 2>&1 || true' EXIT
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  docker run -d --name "$CONTAINER" \
    -e POSTGRES_PASSWORD=selftest -e POSTGRES_USER=selftest -e POSTGRES_DB=postgres \
    postgis/postgis:17-3.4 >/dev/null

  printf 'Waiting for the container'
  until docker exec "$CONTAINER" pg_isready -U selftest -d postgres >/dev/null 2>&1; do
    printf '.'; sleep 2
  done
  echo ' ready'

  docker exec "$CONTAINER" psql -qU selftest -d postgres \
    -c "CREATE DATABASE restored OWNER selftest;" >/dev/null
  docker exec "$CONTAINER" psql -qU selftest -d restored -c "$EXTENSIONS" >/dev/null
  echo "PostGIS, btree_gist and pgcrypto created in the target."

  gzip -dc "$DUMP" | docker exec -i "$CONTAINER" \
    pg_restore -U selftest -d restored --no-owner --no-acl 2>&1 \
    | grep -v 'schema "public" already exists' \
    | grep -v 'CREATE SCHEMA public' \
    | grep -v '^--$' || true

  echo
  echo "Row counts in the restored database:"
  COUNTS=$(docker exec "$CONTAINER" psql -tAF' ' -U selftest -d restored \
             -c "$(count_query)" 2>/dev/null || true)
  FAILED=0
  for t in $EXPECTED_TABLES; do
    N=$(printf '%s\n' "$COUNTS" | awk -v t="$t" '$1==t {print $2}')
    printf '  %-24s %s\n' "$t" "${N:-MISSING}"
    if [ -z "$N" ] || [ "$N" = "0" ]; then FAILED=1; fi
  done

  echo
  if [ "$FAILED" -ne 0 ]; then
    echo "SELF-TEST FAILED: a table is missing or empty. The dump is not a backup." >&2
    exit 1
  fi
  echo "SELF-TEST PASSED: every expected table came back non-empty."
  echo
  echo "This checks the data restores. It does not check that the application"
  echo "starts against it -- Flyway compares its history table to the schema on"
  echo "boot, and that is a separate failure mode. To check it too:"
  echo
  echo "  docker run -d --name pgcheck -p 5433:5432 \\"
  echo "    -e POSTGRES_PASSWORD=t -e POSTGRES_USER=t postgis/postgis:17-3.4"
  echo "  # create the db, create the extensions, restore as above, then:"
  echo "  cd backend && mvn spring-boot:run -Dspring-boot.run.arguments=\\"
  echo "    \"--server.port=8099 \\"
  echo "     --spring.datasource.url=jdbc:postgresql://localhost:5433/restored \\"
  echo "     --spring.datasource.username=t --spring.datasource.password=t\""
  echo "  curl localhost:8099/api/v1/dashboard/summary"
  exit 0
fi

# --------------------------------------------------------------------------
# Real restore.
# --------------------------------------------------------------------------
DUMP="${1:-}"; TARGET_URL="${2:-}"; TARGET_USER="${3:-}"; PW_VAR="${4:-DATABASE_PASSWORD}"
[ -n "$DUMP" ] && [ -n "$TARGET_URL" ] && [ -n "$TARGET_USER" ] || usage
[ -f "$DUMP" ] || { echo "No such dump: $DUMP" >&2; exit 1; }

if [ -z "${!PW_VAR:-}" ]; then
  echo "$PW_VAR is not set. Export it, or source backend/.env.seed first --" >&2
  echo "do not put a password on the command line." >&2
  exit 1
fi

HOST=$(echo "$TARGET_URL" | sed -E 's#^jdbc:postgresql://([^:/]+).*#\1#')
PORT=$(echo "$TARGET_URL" | sed -E 's#^jdbc:postgresql://[^:]+:([0-9]+).*#\1#')
DB=$(echo "$TARGET_URL"   | sed -E 's#.*/([^/?]+)(\?.*)?$#\1#')

echo "About to restore $DUMP into $DB on $HOST:$PORT as $TARGET_USER."
echo
echo "The target MUST be empty. Restoring over a populated database leaves"
echo "Flyway's schema history and the actual schema disagreeing, and the"
echo "application then refuses to start -- which is a worse outcome than the"
echo "data loss you are recovering from, because it takes the running site down"
echo "too."
echo
read -r -p "Type the database name to confirm: " CONFIRM
[ "$CONFIRM" = "$DB" ] || { echo "Aborted." >&2; exit 1; }

run_psql() {
  docker run --rm -i -e PGPASSWORD="${!PW_VAR}" postgres:17-alpine \
    psql -h "$HOST" -p "$PORT" -U "$TARGET_USER" -d "$DB" "$@"
}

echo "Creating extensions in the target (the dump does not carry them)..."
run_psql -q -c "$EXTENSIONS"

echo "Restoring..."
gzip -dc "$DUMP" | docker run --rm -i -e PGPASSWORD="${!PW_VAR}" postgres:17-alpine \
  pg_restore -h "$HOST" -p "$PORT" -U "$TARGET_USER" -d "$DB" --no-owner --no-acl 2>&1 \
  | grep -v 'schema "public" already exists' | grep -v 'CREATE SCHEMA public' || true

echo
echo "Verifying -- pg_restore exits 0 even when it restored nothing:"
COUNTS=$(run_psql -tAF' ' -c "$(count_query)" 2>/dev/null | tr -d '\r' || true)
FAILED=0
for t in $EXPECTED_TABLES; do
  N=$(printf '%s\n' "$COUNTS" | awk -v t="$t" '$1==t {print $2}')
  printf '  %-24s %s\n' "$t" "${N:-MISSING}"
  if [ -z "$N" ] || [ "$N" = "0" ]; then FAILED=1; fi
done
[ "$FAILED" -eq 0 ] || { echo "RESTORE FAILED: a table is missing or empty." >&2; exit 1; }

echo
echo "Restore verified. Start the application and confirm it boots -- Flyway"
echo "checks its history against the schema, which this script does not."
