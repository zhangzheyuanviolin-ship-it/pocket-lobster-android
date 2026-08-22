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
grep -q '"runtimeEnabled": true' "$MANIFEST"
grep -q '"officialProviderManagement": true' "$MANIFEST"
grep -q '"officialChatRuntime": true' "$MANIFEST"
grep -q '"legacyOpenClawRuntimeBundled": false' "$MANIFEST"
grep -q 'golden-beta-v299-20260820' "$MANIFEST"
grep -q 'implementation(project(":openminis"))' "$ROOT_DIR/android/app/build.gradle.kts"
grep -q 'versionCode = 306' "$ROOT_DIR/android/app/build.gradle.kts"
grep -q 'third_party/OpenMinis/src/android/app/src/main/assets' "$ROOT_DIR/android/app/build.gradle.kts"
grep -q 'MinisLauncher.openHome' "$ROOT_DIR/android/app/src/main/java/com/codex/mobile/AgentHubActivity.kt"
grep -q 'minis://settings/providers' "$ROOT_DIR/android/app/src/main/java/com/codex/mobile/MinisLauncher.kt"
grep -q 'android:name=".PocketLobsterApplication"' "$ROOT_DIR/android/app/src/main/AndroidManifest.xml"
grep -q 'android:label="@string/pocket_lobster_app_name"' "$ROOT_DIR/android/app/src/main/AndroidManifest.xml"
grep -q 'android:icon="@mipmap/pocket_lobster_launcher"' "$ROOT_DIR/android/app/src/main/AndroidManifest.xml"
grep -q 'pocket_lobster_app_name' "$ROOT_DIR/android/app/build.gradle.kts"
grep -q 'class PocketLobsterApplication : MinisApp()' \
  "$ROOT_DIR/android/app/src/main/java/com/codex/mobile/PocketLobsterApplication.kt"
grep -q 'processName == "\$packageName:minis"' \
  "$ROOT_DIR/android/app/src/main/java/com/codex/mobile/PocketLobsterApplication.kt"
grep -q 'ShizukuProvider.enableMultiProcessSupport' \
  "$ROOT_DIR/android/app/src/main/java/com/codex/mobile/PocketLobsterApplication.kt"
grep -q 'ShizukuProvider.requestBinderForNonProviderProcess' \
  "$ROOT_DIR/android/app/src/main/java/com/codex/mobile/PocketLobsterApplication.kt"
grep -q '口袋大龙虾测试版-启动诊断.jsonl' \
  "$ROOT_DIR/android/app/src/main/java/com/codex/mobile/PocketLobsterApplication.kt"
grep -q 'open class MinisApp' "$ROOT_DIR/android/openminis/build.gradle.kts"
grep -q 'native-offload-.*Process.myUid' "$ROOT_DIR/android/openminis/build.gradle.kts"
grep -q 'minis_app_name' "$ROOT_DIR/android/openminis/build.gradle.kts"
grep -q 'PocketLobsterHostTools.localTerminalDefinition' "$ROOT_DIR/android/openminis/build.gradle.kts"
grep -q 'PocketLobsterHostTools.ubuntuDefinition' "$ROOT_DIR/android/openminis/build.gradle.kts"
grep -q 'com.openminis.app.integration.SharedBrowserActivity' "$ROOT_DIR/android/app/src/main/AndroidManifest.xml"
grep -q 'com.openminis.app.integration.MinisRuntimeBridgeService' "$ROOT_DIR/android/app/src/main/AndroidManifest.xml"
grep -q 'tool("anyclaw_alpine"' "$ROOT_DIR/android/app/src/main/assets/anyclaw/claude-toolbox-server.js"
grep -q 'tool("minis_browser"' "$ROOT_DIR/android/app/src/main/assets/anyclaw/claude-toolbox-server.js"
test -f "$ROOT_DIR/android/app/src/main/assets/shared-runtime/alpine-shell"
test -f "$ROOT_DIR/android/app/src/main/assets/shared-runtime/minis-browser"
test -f "$ROOT_DIR/android/app/src/main/assets/shared-runtime/shared-runtime-cli.js"
test "$(grep -c 'android:process=":minis"' "$ROOT_DIR/android/openminis/src/main/AndroidManifest.xml")" -eq 10
test -f "$ROOT_DIR/android/openminis/build.gradle.kts"
test -f "$ROOT_DIR/android/app/src/main/res/mipmap-anydpi-v26/pocket_lobster_launcher.xml"
test -f "$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a/libubuntu_proot.so"

printf 'OpenMinis integration verified: tag=%s commit=%s runtime_enabled=true\n' "$actual_tag" "$actual_commit"
