import { strict as assert } from 'node:assert'
import { build } from 'esbuild'
import { test } from 'node:test'

const gatewayNames = ['archiveThread', 'decodeModelValue', 'forkThread', 'getAvailableModels',
  'getCurrentModelConfig', 'getPendingServerRequests', 'getThreadRoute', 'getThreadSnapshot',
  'interruptThreadTurn', 'rollbackThread', 'replyToServerRequest', 'getThreadGroups', 'resumeThread',
  'setActiveModelSelection', 'startThread', 'subscribeCodexNotifications', 'startThreadTurn', 'switchThreadRoute']
const bundle = await build({
  entryPoints: ['src/composables/useDesktopState.ts'], bundle: true, write: false,
  platform: 'node', format: 'esm', plugins: [{ name: 'fake-gateway', setup(builder) {
    builder.onResolve({ filter: /\/codexGateway$/ }, () => ({ path: 'gateway', namespace: 'test' }))
    builder.onLoad({ filter: /.*/, namespace: 'test' }, () => ({ contents: gatewayNames.map(name =>
      `export const ${name} = (...args) => globalThis.__historyGateway.${name}(...args);`).join('\n') }))
  }}],
})
const { useDesktopState } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`)
const tick = () => new Promise(resolve => setImmediate(resolve))
const deferred = () => { let resolve; let reject; const promise = new Promise((a,b) => {resolve=a; reject=b}); return {promise,resolve,reject} }
const snapshot = (id) => ({ messages: [{ id, role: 'assistant', text: id, createdAtIso: '2026-09-07T00:00:00Z' }], latestTurnId: '', latestTurnStatus: '', latestTurnError: '' })
function setup() {
  const storage = new Map()
  globalThis.window = { setTimeout, clearTimeout, setInterval, clearInterval,
    localStorage: { getItem: key => storage.get(key) ?? null, setItem: (k,v) => storage.set(k,v) } }
  globalThis.localStorage = window.localStorage
  globalThis.__historyGateway = Object.fromEntries(gatewayNames.map(name => [name, async () => undefined]))
  Object.assign(__historyGateway, { getThreadGroups: async () => [{ projectName: 'test', threads: ['A','B'].map(id => ({id,title:id,cwd:'/tmp',updatedAtIso:'2026-09-07T00:00:00Z'})) }],
    getAvailableModels: async () => [], getCurrentModelConfig: async () => ({modelValue:'',reasoningEffort:'medium',signature:''}),
    getThreadRoute: async () => ({providerId:'openai',model:''}) })
  return useDesktopState()
}

test('foreground load and silent refresh cannot strand loading or discard the only snapshot', async () => {
  const state = setup()
  __historyGateway.getThreadSnapshot = async id => snapshot(id)
  await state.refreshAll()
  await state.selectThread('B')
  const reads = []
  __historyGateway.getThreadSnapshot = async () => { const d=deferred(); reads.push(d); return d.promise }
  const first = state.selectThread('C')
  await tick()
  const refresh = state.refreshAll()
  await tick()
  for (const read of reads) read.resolve(snapshot('C'))
  await Promise.all([first,refresh])
  assert.equal(state.isLoadingMessages.value, false)
  assert.ok(state.messages.value.length > 0)
})

test('switching to an already loaded thread is not blocked by a different pending thread', async () => {
  const state = setup()
  __historyGateway.getThreadSnapshot = async id => snapshot(id)
  await state.selectThread('B')
  const slow = deferred()
  __historyGateway.getThreadSnapshot = async id => id === 'A' ? slow.promise : snapshot(id)
  const first = state.selectThread('A')
  await tick()
  await state.selectThread('B')
  assert.equal(state.selectedThreadId.value, 'B')
  assert.equal(state.isLoadingMessages.value, false)
  slow.resolve(snapshot('A'))
  await first
  assert.equal(state.selectedThreadId.value, 'B')
})

test('failed history load clears its request and can be retried from the same thread', async () => {
  const state = setup()
  let attempt = 0
  __historyGateway.getThreadSnapshot = async id => {
    attempt += 1
    if (attempt === 1) throw new Error('temporary read failure')
    return snapshot(id)
  }
  await state.selectThread('A')
  assert.equal(state.isLoadingMessages.value, false)
  assert.match(state.error.value, /temporary read failure/)
  await state.selectThread('A')
  assert.equal(state.isLoadingMessages.value, false)
  assert.equal(state.error.value, '')
  assert.equal(state.messages.value.at(-1)?.text, 'A')
})
