#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OPENMINIS_DIR="$ROOT_DIR/third_party/OpenMinis"
MANIFEST="$ROOT_DIR/android/app/src/main/assets/integration/openminis-upstream.json"
EXPECTED_COMMIT="09fc199928de0f26685e766c34e6d541c7a69e5a"
EXPECTED_TAG="1.12"
EXPECTED_LICENSE_SHA="3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986"
EXPECTED_NOTICES_SHA="7998ec155c04ff96d87d4f2cd1f594f5c3bd7447c729351249c2471ccafdeae9"

test -d "$OPENMINIS_DIR/.git" || test -f "$OPENMINIS_DIR/.git"
test -f "$OPENMINIS_DIR/LICENSE"
test -f "$OPENMINIS_DIR/THIRD_PARTY_LICENSES.md"
test -f "$OPENMINIS_DIR/src/android/app/build.gradle.kts"
test -f "$MANIFEST"

actual_commit="$(git -C "$OPENMINIS_DIR" rev-parse HEAD)"
test "$actual_commit" = "$EXPECTED_COMMIT"

actual_tag="$(git -C "$OPENMINIS_DIR" describe --tags --exact-match HEAD)"
test "$actual_tag" = "$EXPECTED_TAG"

actual_license_sha="$(sha256sum "$OPENMINIS_DIR/LICENSE" | awk '{print $1}')"
test "$actual_license_sha" = "$EXPECTED_LICENSE_SHA"

actual_notices_sha="$(sha256sum "$OPENMINIS_DIR/THIRD_PARTY_LICENSES.md" | awk '{print $1}')"
test "$actual_notices_sha" = "$EXPECTED_NOTICES_SHA"

grep -q 'compileSdk = 36' "$OPENMINIS_DIR/src/android/app/build.gradle.kts"
grep -q 'minSdk = 26' "$OPENMINIS_DIR/src/android/app/build.gradle.kts"
grep -q 'versionName = "1.12"' "$OPENMINIS_DIR/src/android/app/build.gradle.kts"
grep -q '09fc199928de0f26685e766c34e6d541c7a69e5a' "$MANIFEST"
grep -q '"runtimeEnabled": false' "$MANIFEST"
grep -q 'golden-beta-v299-20260820' "$MANIFEST"

printf 'OpenMinis foundation verified: tag=%s commit=%s runtime_enabled=false\n' "$actual_tag" "$actual_commit"
