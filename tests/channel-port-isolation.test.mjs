import { readFileSync } from 'node:fs'
import { strict as assert } from 'node:assert'

const read = (path) => readFileSync(path, 'utf8')
const policy = read('android/openminis/src/main/java/com/openminis/app/integration/PocketLobsterPortPolicy.kt')
const manager = read('android/app/src/main/java/com/codex/mobile/CodexServerManager.kt')
const foregroundService = read('android/app/src/main/java/com/codex/mobile/CodexForegroundService.kt')
const nativeMinisClient = read('android/openminis/src/main/java/com/openminis/app/integration/CollaborationClient.kt')
const minisCollaborationTools = read('android/openminis/src/main/java/com/openminis/app/integration/PocketLobsterCollaborationTools.kt')
const minisHostTools = read('android/openminis/src/main/java/com/openminis/app/integration/PocketLobsterHostTools.kt')
const hostRuntime = read('android/app/src/main/java/com/codex/mobile/ShizukuBridgeRuntime.kt')
const proxy = read('android/app/src/main/assets/proxy.js')
const server = read('src/server/codexAppServerBridge.ts')
const app = read('src/App.vue')
const gradle = read('android/app/build.gradle.kts')

for (const service of [
  'appServerPort',
  'proxyPort',
  'hostBridgePort',
  'minisBridgePort',
  'openClawGatewayPort',
  'openClawControlUiPort',
]) {
  assert.match(policy, new RegExp(`fun ${service}\\(packageName: String\\): Int`))
}
assert.match(policy, /PRODUCTION_PACKAGE -> 0/)
assert.match(policy, /OPERATOR_PACKAGE -> 10/)
assert.match(policy, /BETA_PACKAGE -> 20/)
assert.match(manager, /PocketLobsterPortPolicy\.appServerPort\(BuildConfig\.APPLICATION_ID\)/)
assert.match(manager, /POCKET_LOBSTER_PROXY_PORT/)
assert.match(manager, /ANYCLAW_MINIS_BRIDGE_URL/)
assert.match(foregroundService, /ensureShizukuBridgeScripts\(\)[\s\S]*ensureCollaborationRuntimeAssets\(\)/)
assert.match(nativeMinisClient, /PocketLobsterPortPolicy\.appServerPort\(context\.packageName\)/)
assert.match(minisCollaborationTools, /PocketLobsterPortPolicy\.appServerPort\(context\.packageName\)/)
assert.match(minisHostTools, /PocketLobsterPortPolicy\.hostBridgePort\(context\.packageName\)/)
assert.match(hostRuntime, /PocketLobsterPortPolicy\.hostBridgePort\(context\.packageName\)/)
assert.match(proxy, /process\.env\.POCKET_LOBSTER_PROXY_PORT/)
assert.match(server, /process\.env\.ANYCLAW_MINIS_BRIDGE_URL/)
assert.match(app, /serverPort - 18923/)
assert.match(gradle, /buildConfig = true/)

assert.doesNotMatch(nativeMinisClient, /SERVER_PORT = 18923/)
assert.doesNotMatch(minisCollaborationTools, /HOST_URL = "http:\/\/127\.0\.0\.1:18923/)
assert.doesNotMatch(minisHostTools, /HOST_BRIDGE_URL = "http:\/\/127\.0\.0\.1:18926/)
