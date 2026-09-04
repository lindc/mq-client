#!/bin/sh
# Start the MQ Client desktop app (Linux / macOS).
set -e
cd "$(dirname "$0")"
chmod +x gradlew 2>/dev/null || true
exec ./gradlew :app-ui:run "$@"

#$ https://materialkolor.com/?color_seed=FF648042&color_primary=FF648042&dark_mode=false&style=Vibrant&package_name=com.example.app