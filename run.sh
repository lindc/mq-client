#!/bin/sh
# Start the MQ Client desktop app (Linux / macOS).
set -e
cd "$(dirname "$0")"
chmod +x gradlew 2>/dev/null || true
exec ./gradlew :app-ui:run "$@"
