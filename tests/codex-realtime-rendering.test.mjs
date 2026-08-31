import { readFileSync } from 'node:fs'
import { strict as assert } from 'node:assert'

const state = readFileSync('src/composables/useDesktopState.ts', 'utf8')
const rpcClient = readFileSync('src/api/codexRpcClient.ts', 'utf8')
const bridge = readFileSync('src/server/codexAppServerBridge.ts', 'utf8')
const normalizer = readFileSync('src/api/normalizers/v2.ts', 'utf8')
const layout = readFileSync('src/components/layout/DesktopLayout.vue', 'utf8')
const tree = readFileSync('src/components/sidebar/SidebarThreadTree.vue', 'utf8')
const gateway = readFileSync('src/api/codexGateway.ts', 'utf8')

assert.match(rpcClient, /fetchWithTimeout\('\/codex-api\/rpc'/)
assert.match(rpcClient, /RPC_TIMEOUT_BY_METHOD/)
assert.match(rpcClient, /source\.onerror/)

assert.match(bridge, /CODEX_NOTIFICATION_HISTORY_LIMIT = 500/)
assert.match(bridge, /listNotificationsAfter/)
assert.match(bridge, /res\.write\(`id:/)
assert.match(bridge, /Codex RPC \$\{method\} timed out/)

const statusSync = state.match(
  /async function syncThreadStatus[\s\S]*?\n  async function syncFromNotifications/,
)?.[0] ?? ''
assert.ok(statusSync)
assert.ok(statusSync.indexOf('await loadMessages') < statusSync.indexOf('await loadThreads'))
assert.doesNotMatch(statusSync, /isPolling/)

const notificationSync = state.match(
  /async function syncFromNotifications[\s\S]*?\n  function startPolling/,
)?.[0] ?? ''
assert.match(notificationSync, /Promise\.all\(tasks\)/)
assert.match(notificationSync, /messageSyncFailed/)
assert.match(notificationSync, /threadSyncFailed/)

const newThreadFlow = state.match(
  /async function sendMessageToNewThread[\s\S]*?\n  async function startTurnForThread/,
)?.[0] ?? ''
assert.match(newThreadFlow, /registerProvisionalThread/)

const startTurnFlow = state.match(
  /async function startTurnForThread[\s\S]*?\n  async function interruptSelectedThreadTurn/,
)?.[0] ?? ''
assert.match(startTurnFlow, /void syncFromNotifications\(\)/)
assert.doesNotMatch(startTurnFlow, /await syncFromNotifications\(\)/)

const loadThreadsFlow = state.match(
  /async function loadThreads[\s\S]*?\n  async function loadMessages/,
)?.[0] ?? ''
assert.match(loadThreadsFlow, /selectedExistedBeforeRefresh/)
assert.match(loadThreadsFlow, /options\.allowEmpty !== true/)
assert.match(loadThreadsFlow, /pendingThreadIds\.add\(selectedId\)/)
assert.match(gateway, /isThreadMaterializationPending/)
assert.match(gateway, /THREAD_MATERIALIZATION_RETRIES/)
assert.match(gateway, /thread\/read returned no payload/)

for (const rawToolType of ['commandExecution', 'fileChange', 'mcpToolCall', 'collabAgentToolCall', 'webSearch', 'imageView']) {
  assert.doesNotMatch(normalizer, new RegExp(`item\\.type === '${rawToolType}'`))
}
assert.doesNotMatch(normalizer, /终端命令|退出码|aggregatedOutput/)
assert.doesNotMatch(state, /activity\.live|queueLiveToolDelta|readToolOutputDelta/)
assert.match(normalizer, /item\.type === 'reasoning'/)

assert.doesNotMatch(layout, /Resize sidebar/)
assert.doesNotMatch(tree, /title="project_menu"/)
assert.doesNotMatch(tree, /thread-start-button"/)
