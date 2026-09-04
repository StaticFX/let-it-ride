#!/usr/bin/env bash
#
# Builds and runs the artefact the suite tests: one jar serving the API and the
# bundled SPA on one port. Playwright starts this and waits on /api/health.
#
# E2E_SKIP_BUILD=1 reuses whatever jar is already there, which is what you want
# when you are only editing tests.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
jar="$root/backend/build/libs/let-it-ride.jar"

if [ "${E2E_SKIP_BUILD:-0}" = "1" ] && [ -f "$jar" ]; then
  echo "e2e: reusing $jar"
else
  echo "e2e: building the frontend bundle and the fat jar…"
  "$root/gradlew" -p "$root" --console=plain :backend:buildFrontend :backend:buildFatJar
fi

if [ ! -f "$jar" ]; then
  echo "e2e: expected a jar at $jar but the build did not produce one" >&2
  exit 1
fi

echo "e2e: serving $jar on port ${PORT:-8080} (test hooks: ${LETITRIDE_TEST_HOOKS:-off}, pace: ${LETITRIDE_PACE:-1})"
exec java -jar "$jar"
