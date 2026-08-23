import { readFileSync } from 'node:fs'
import { strict as assert } from 'node:assert'

const cli = readFileSync('android/app/src/main/assets/shared-runtime/shared-runtime-cli.js', 'utf8')
const bridge = readFileSync('android/openminis/src/main/java/com/openminis/app/integration/MinisRuntimeBridge.kt', 'utf8')
const openMinisBuild = readFileSync('android/openminis/build.gradle.kts', 'utf8')
const claudeToolbox = readFileSync('android/app/src/main/assets/anyclaw/claude-toolbox-server.js', 'utf8')
const claudePrompt = readFileSync('android/app/src/main/java/com/codex/mobile/CliAgentChatActivity.kt', 'utf8')
const codexBridge = readFileSync('src/server/codexAppServerBridge.ts', 'utf8')
const minisHostTools = readFileSync('android/openminis/src/main/java/com/openminis/app/integration/PocketLobsterHostTools.kt', 'utf8')

for (const action of ['navigate', 'back', 'forward', 'reload', 'screenshot', 'click', 'type', 'wait_for_dom_stable']) {
  assert.match(cli, new RegExp(`${action}:`))
}
assert.match(cli, /minis-browser --help/)
assert.match(cli, /inputSchema: BROWSER_SCHEMA/)
assert.match(cli, /selector-type css\|xpath\|text/)
assert.match(cli, /image_path:/)
assert.match(cli, /image_mime_type:/)

assert.match(bridge, /browser\/schema/)
assert.match(bridge, /Bitmap\.CompressFormat\.PNG/)
assert.match(bridge, /imageMimeType/)
assert.match(bridge, /include_base64/)
assert.match(bridge, /selector_type/)
assert.match(bridge, /Page context after retries/)

assert.match(openMinisBuild, /BACK\(\"back\"\)/)
assert.match(openMinisBuild, /FORWARD\(\"forward\"\)/)
assert.match(openMinisBuild, /RELOAD\(\"reload\"\)/)
assert.match(openMinisBuild, /obj\.has\(\"timeout_ms\"\)/)
assert.match(openMinisBuild, /evaluateSelectorWithRetry/)

assert.match(claudeToolbox, /version: \"2\.3\.0\"/)
assert.match(claudeToolbox, /type: \"image\"/)
assert.match(claudeToolbox, /mimeType: String\(result\.imageMimeType \|\| \"image\/png\"\)/)
assert.match(claudeToolbox, /anyclaw_terminal[\s\S]*returns ok, exitCode, stdout, stderr, error, and cwd/)
assert.match(claudeToolbox, /anyclaw_ubuntu[\s\S]*runtime=ubuntu/)
assert.match(claudeToolbox, /anyclaw_alpine[\s\S]*explicit bridge errors/)

assert.match(claudePrompt, /list_tabs、navigate、wait_for_dom_stable/)
assert.match(claudePrompt, /直接返回PNG图像内容和imageFilePath/)
assert.match(codexBridge, /Run minis-browser --help before first use/)
assert.match(codexBridge, /screenshot --output-path <absolute-path\.png> --json/)
assert.match(minisHostTools, /app-local Android terminal/)
assert.match(minisHostTools, /bundled Ubuntu Linux bridge/)
