#!/bin/sh
# Start the MQ Client desktop app (Linux / macOS).
set -e
cd "$(dirname "$0")"

# Prefer a locally installed Gradle (GRADLE_HOME, then PATH) so we skip the
# wrapper's distribution download. Falls back to ./gradlew when none is found.
GRADLE_CMD=""
if [ -n "${GRADLE_HOME:-}" ] && [ -x "$GRADLE_HOME/bin/gradle" ]; then
  GRADLE_CMD="$GRADLE_HOME/bin/gradle"
elif [ -x "$(command -v gradle 2>/dev/null)" ]; then
  GRADLE_CMD="$(command -v gradle)"
fi

if [ -n "$GRADLE_CMD" ]; then
  echo "Using local Gradle: $GRADLE_CMD"
  exec "$GRADLE_CMD" :app-ui:run "$@"
fi

chmod +x gradlew 2>/dev/null || true
exec ./gradlew :app-ui:run "$@"

