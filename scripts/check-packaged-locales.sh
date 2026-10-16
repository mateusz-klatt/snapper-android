#!/usr/bin/env bash
set -euo pipefail

project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
apk=${1:-"$project_dir/app/build/outputs/apk/debug/app-debug.apk"}
aapt2="${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}/build-tools/36.0.0/aapt2"
configurations=$("$aapt2" dump configurations "$apk")
catalog_count=0

for catalog in "$project_dir"/app/src/main/res/values-*/strings.xml; do
    directory=${catalog%/strings.xml}
    qualifier=${directory##*/values-}
    if [[ "$qualifier" =~ ^b\+([a-z]{2,3})$ ]]; then
        qualifier=${BASH_REMATCH[1]}
    elif [[ "$qualifier" =~ ^b\+([a-z]{2,3})\+([A-Z]{2})$ ]]; then
        qualifier="${BASH_REMATCH[1]}-r${BASH_REMATCH[2]}"
    fi
    if ! grep -Fxq -- "$qualifier" <<< "$configurations"; then
        printf 'Missing packaged translation catalog: %s\n' "$qualifier" >&2
        exit 1
    fi
    catalog_count=$((catalog_count + 1))
done

printf 'Verified %s packaged translation catalogs in %s.\n' "$catalog_count" "$apk"
