#!/usr/bin/env bash
set -euo pipefail

echo "=== [1/4] Creating Android English resource directory ==="
mkdir -p android/app/src/main/res/values-en

echo "=== [2/4] Writing android/app/src/main/res/values-en/strings.xml ==="
cat << 'XML' > android/app/src/main/res/values-en/strings.xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Pocket Lobster</string>
    <string name="agent_hub_title">Pocket Lobster Agent Hub</string>
    <string name="conversation_exported_toast">Exported to Download/PocketLobster/exports.</string>
    <string name="storage_permission_required_message_android_r">Pocket Lobster needs full file access to read and write shared storage (for example /sdcard). Grant \"All files access\" to continue.</string>
    <string name="storage_permission_required_message_legacy">Pocket Lobster needs storage permission to read and write shared storage (for example /sdcard). Grant permission to continue.</string>
    <string name="shizuku_revoke_hint">To completely revoke access: Tap \"Open Shizuku app\" and disable authorization for Pocket Lobster.</string>
</resources>
XML

echo "=== [3/4] Updating Kotlin Native Overlays ==="
# Update PhoneUiAgentProgressOverlay title if file exists
OVERLAY_FILE="android/app/src/main/java/com/codex/mobile/PhoneUiAgentProgressOverlay.kt"
if [ -f "$OVERLAY_FILE" ]; then
  sed -i 's/title = "口袋大龙虾手机操作进度"/title = "Pocket Lobster UI Agent Progress"/g' "$OVERLAY_FILE"
  echo "Updated $OVERLAY_FILE"
fi

BOOTSTRAP_FILE="android/app/src/main/java/com/codex/mobile/PhoneUiAgentBootstrapActivity.kt"
if [ -f "$BOOTSTRAP_FILE" ]; then
  sed -i 's/text = "口袋大龙虾虚拟屏幕已就绪/text = "Pocket Lobster virtual screen ready/g' "$BOOTSTRAP_FILE"
  echo "Updated $BOOTSTRAP_FILE"
fi

echo "=== [4/4] Setup complete! Verifying repository status ==="
git status
echo "Run 'git diff' to review changes, then execute local build tests."
