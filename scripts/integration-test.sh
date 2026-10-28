#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v docker >/dev/null 2>&1 || { echo "Docker with Compose is required; PostgreSQL integration tests were not run." >&2; exit 1; }
# A unique project owns only this temporary test container; existing demo data is untouched.
project="tape-it-$(date +%s)-$$"
cleanup() { docker compose -p "$project" --profile test down --remove-orphans >/dev/null; }
trap cleanup EXIT
export IT_DB_PORT="${IT_DB_PORT:-15432}"
export IT_DB_URL="jdbc:postgresql://localhost:$IT_DB_PORT/tape_it"
export IT_DB_USER=tape_test IT_DB_PASSWORD=tape_test_only
docker compose -p "$project" --profile test up -d --wait test-db
./mvnw -B -ntp -Pintegration verify
