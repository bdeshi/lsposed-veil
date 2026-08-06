#!/usr/bin/env bash
# Builds a release APK and copies it into dist/ with a version-stamped filename.
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew assembleRelease

VERSION=$(grep 'versionName' app/build.gradle.kts | head -1 | sed -E 's/.*"(.*)".*/\1/')
APK="app/build/outputs/apk/release/app-release.apk"
OUT="dist/veil-${VERSION}.apk"

mkdir -p dist
cp "$APK" "$OUT"

echo "Built $OUT"
