#!/usr/bin/env bash
# Puts the APK from a green CI run on the Mush server (served at /android/mush.apk).
# Usage: tools/fetch-apk.sh [run-id]   (default: the latest successful run on main)
set -euo pipefail
cd "$(dirname "$0")/.."
run="${1:-$(gh run list --branch main --workflow ci --status success --limit 1 --json databaseId -q '.[0].databaseId')}"
tmp=$(mktemp -d)
gh run download "$run" -n mush-android -D "$tmp"
mkdir -p data/android
mv "$tmp/mush.apk" data/android/mush.apk.new && mv data/android/mush.apk.new data/android/mush.apk
mv "$tmp/version.json" data/android/version.json
rmdir "$tmp"
echo "served: $(cat data/android/version.json)"
