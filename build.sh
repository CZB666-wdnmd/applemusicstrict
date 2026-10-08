#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if ! command -v gradle >/dev/null; then
  echo 'Install Gradle 8.9, JDK 17 and Android SDK platform 35/build-tools 34.0.0, or build in Android Studio.' >&2
  exit 1
fi
gradle --no-daemon :app:assembleDebug :app:lintDebug
