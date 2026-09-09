import { readFile } from 'node:fs/promises'
import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import vm from 'node:vm'
import { transform } from 'esbuild'

async function loadFunction(path, from, to, globals) {
  const source = await readFile(path, 'utf8')
  const body = source.slice(source.indexOf(from), source.indexOf(to, source.indexOf(from)))
  const name = /function (\w+)/.exec(from)[1]
  const { code } = await transform(`${body}\nglobalThis.target = ${name}`, { loader: 'ts' })
  const scope = vm.createContext(globals)
  vm.runInContext(code, scope)
  return scope.target
}

test('standalone send failures never open collaboration, even if the toggle changes while awaiting', async () => {
  for (const toggleDuringSend of [false, true]) {
    const enabled = { value: false }
    const board = { value: false }
    const error = { value: '' }
    const send = await loadFunction('src/App.vue', 'async function onSubmitThreadMessage(', '\nfunction loadCollaborationEnabled(', {
      collaborationEnabled: enabled, showCollaborationBoard: board, collaborationError: error,
      isHomeRoute: { value: false },
      sendMessageToSelectedThread: async () => { enabled.value = toggleDuringSend; throw Error('provider failure') },
    })
    let completed
    await send('继续开发', ok => { completed = ok })
    assert.equal(completed, false)
    assert.equal(board.value, false)
    assert.equal(error.value, '')
  }
})

test('live resume route wins over stale thread metadata in both directions', async () => {
  for (const [previous, selected] of [['openai', 'pocket_provider_a'], ['pocket_provider_a', 'openai']]) {
    const events = []
    const switchRoute = await loadFunction('src/server/codexAppServerBridge.ts', 'async function switchCodexThreadRoute(', '\nfunction normalizeCollaborationAgent(', {
      appendCodexDiagnostic: async () => {}, validateCodexProviderModel: async () => {},
      ensureCodexProviderDefinitions: async () => {}, asRecord: value => value,
      normalizeText: value => typeof value === 'string' ? value.trim() : '',
      rememberedCodexThreadRoute: async () => null,
      migratePersistedThreadRoute: async () => { events.push('migrate'); return {} },
      restorePersistedThreadRoute: async () => {}, buildInjectedDeveloperInstructions: async () => '',
      rememberCodexThreadRoute: async (_, provider) => { events.push(provider) },
      getErrorMessage: e => e.message,
    })
    const engine = {
      dispose: () => events.push('stop'),
      waitUntilStopped: async () => events.push('stopped'),
      rpc: async (method) => method === 'thread/read' ? { thread: { modelProvider: previous } } :
        method === 'thread/resume' ? { modelProvider: selected, thread: { id: 'same-thread', modelProvider: previous } } : {},
    }
    const result = await switchRoute(engine, 'same-thread', selected, 'model')
    assert.equal(result.providerId, selected)
    assert.equal(result.thread.id, 'same-thread')
    assert.equal(result.thread.modelProvider, selected)
    assert.ok(events.indexOf('stopped') < events.indexOf('migrate'))
    assert.equal(events.at(-1), selected)
  }
})

test('real route mismatch is not hidden and rolls back migration', async () => {
  let restored = false
  const switchRoute = await loadFunction('src/server/codexAppServerBridge.ts', 'async function switchCodexThreadRoute(', '\nfunction normalizeCollaborationAgent(', {
    appendCodexDiagnostic: async () => {}, validateCodexProviderModel: async () => {}, ensureCodexProviderDefinitions: async () => {},
    asRecord: value => value, normalizeText: value => typeof value === 'string' ? value : '',
    rememberedCodexThreadRoute: async () => null, migratePersistedThreadRoute: async () => ({}),
    restorePersistedThreadRoute: async () => { restored = true }, buildInjectedDeveloperInstructions: async () => '',
    rememberCodexThreadRoute: async () => assert.fail('must not persist an unverified provider'), getErrorMessage: e => e.message,
  })
  await assert.rejects(switchRoute({ dispose() {}, async waitUntilStopped() {},
    async rpc() { return { modelProvider: 'openai', thread: { modelProvider: 'openai' } } },
  }, 'thread', 'pocket_provider_a', 'model'), /route mismatch/)
  assert.equal(restored, true)
})
