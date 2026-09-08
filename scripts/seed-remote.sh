#!/usr/bin/env bash
# Seeds a remote database from local, reading credentials from a gitignored
# file so they never reach the shell history or a screenshot.
#
# Usage:  ./scripts/seed-remote.sh
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
  echo "Take the host from Supabase -> Connect -> Session pooler (port 5432)." >&2
  exit 1
fi
for v in DATABASE_PASSWORD JWT_SECRET; do
  if [ -z "${!v:-}" ]; then echo "$v is empty in $ENV_FILE" >&2; exit 1; fi
done

# The seed run must not carry the production signing key. It issues no tokens
# anybody will present, so there is no reason for the real secret to be here --
# and every reason not to have it on a laptop in a file.
if [ "${JWT_SECRET}" != "seed-run-only-not-a-real-secret-do-not-deploy-this-value" ]; then
  echo "Warning: JWT_SECRET in $ENV_FILE is not the throwaway value." >&2
  echo "The seed run does not need your production secret. Consider replacing it" >&2
  echo "with the default from .env.seed.example." >&2
fi

echo "Seeding ${DATABASE_URL##*//}"
CORPUS=$(sed -n 's/^ *corpus-size: *\([0-9]*\).*/\1/p' backend/src/main/resources/application-seed.yml | head -1)
echo "This ingests ${CORPUS:-?} reports through the real clustering engine, one"
echo "round trip each, so expect a few minutes over the internet. Ctrl-C when"
echo "it reports it is done."
echo

cd backend
# --server.port=0 binds an ephemeral port instead of 8080.
#
# The seed is a CommandLineRunner: it writes rows and exits. It has no use for
# an HTTP listener, and defaulting to 8080 means it collides with any locally
# running instance of this same application -- which is exactly the machine
# somebody is most likely to run this from. Port 0 removes the conflict without
# changing how the application is wired.
exec mvn spring-boot:run \
  -Dspring-boot.run.profiles=seed \
  -Dspring-boot.run.arguments="--server.port=0"
