import assert from 'node:assert/strict'
import test from 'node:test'
import {
  createProviderResponseContext,
  migratePersistedThreadText,
  normalizePersistedThreadOrdinals,
  normalizeResponseItemId,
  prepareProviderRequest,
  repairResponseItemIds,
  sanitizeProviderResponse,
} from '../src/server/codexProviderProtocol.mjs'

test('normalizes ordinal gaps without changing other rollout content', () => {
  const raw = [
    JSON.stringify({ ordinal: 10, type: 'session_meta', payload: {} }),
    JSON.stringify({ ordinal: 12, type: 'event_msg', payload: { type: 'task_started' } }),
    '',
  ].join('\n')
  const normalized = normalizePersistedThreadOrdinals(raw)
  const rows = normalized.text.trim().split('\n').map(JSON.parse)
  assert.equal(normalized.changed, true)
  assert.deepEqual(rows.map((row) => row.ordinal), [10, 11])
  assert.equal(rows[1].payload.type, 'task_started')
})

test('renumbers persisted rollout ordinals after foreign provider state is removed', () => {
  const rows = [
    { ordinal: 0, type: 'session_meta', payload: { model_provider: 'openai' } },
    { ordinal: 1, type: 'response_item', payload: { type: 'reasoning', id: 'rs_openai' } },
    { ordinal: 2, type: 'response_item', payload: { type: 'compaction', id: 'cmp_openai' } },
    { ordinal: 3, type: 'response_item', payload: { type: 'web_search_call', id: 'call_foreign_search' } },
    { ordinal: 4, type: 'event_msg', payload: { type: 'response.web_search_call.completed', item_id: 'call_foreign_search' } },
  ]
  const migrated = migratePersistedThreadText(
    `${rows.map((row) => JSON.stringify(row)).join('\n')}\n`,
    'pocket_provider_minimax',
    true,
  )
  const output = migrated.text.trim().split('\n').map((line) => JSON.parse(line))
  assert.equal(migrated.providerMetadataFound, true)
  assert.equal(migrated.sanitizedReasoningItems, 1)
  assert.equal(migrated.removedCompactionItems, 1)
  assert.equal(migrated.repairedResponseItemIds, 2)
  assert.deepEqual(output.map((row) => row.ordinal), [0, 1, 2])
  assert.equal(output[0].payload.model_provider, 'pocket_provider_minimax')
  assert.match(output[1].payload.id, /^ws_/)
  assert.equal(output[2].payload.item_id, output[1].payload.id)
})

test('normalizes foreign response item ids with stable Codex prefixes', () => {
  const first = normalizeResponseItemId('message', 'foreign_msg_5')
  assert.match(first, /^msg_/)
  assert.equal(first, normalizeResponseItemId('message', 'foreign_msg_5'))
  assert.equal(normalizeResponseItemId('message', 'msg_valid'), 'msg_valid')
  assert.match(normalizeResponseItemId('function_call', 'foreign_fc_0'), /^fc_/)
  assert.match(normalizeResponseItemId('web_search_call', 'call_foreign_search'), /^ws_/)
})

test('restores Codex local tools declared through Responses Lite additional_tools', () => {
  const prepared = prepareProviderRequest({
    model: 'MiniMax-M3',
    tools: null,
    input: [{
      type: 'additional_tools',
      role: 'developer',
      tools: [
        { type: 'custom', name: 'exec', description: 'Run JavaScript' },
        {
          type: 'function',
          name: 'apply_patch',
          description: 'Apply a patch',
          parameters: { type: 'object', properties: { input: { type: 'string' } } },
        },
        {
          type: 'function',
          name: 'list_mcp_resources',
          description: 'List resources',
          parameters: { type: 'object', properties: {} },
        },
      ],
    }],
  })

  assert.deepEqual(prepared.customToolNames, ['exec', 'apply_patch'])
  assert.equal(prepared.payload.input[0].tools[0].type, 'function')
  assert.equal(prepared.payload.input[0].tools[1].type, 'function')
  assert.equal(prepared.payload.input[0].tools[2].type, 'function')

  const execDone = sanitizeProviderResponse({
    type: 'response.output_item.done',
    item: {
      type: 'function_call',
      id: 'foreign_exec',
      call_id: 'call_exec',
      name: 'exec',
      arguments: '{"input":"text(true)"}',
    },
  }, createProviderResponseContext(prepared.customToolNames))
  assert.equal(execDone.item.type, 'custom_tool_call')
  assert.equal(execDone.item.input, 'text(true)')
})

test('maps Codex custom tools through function-only compatible providers', () => {
  const prepared = prepareProviderRequest({
    model: 'third-party-model',
    tools: [{ type: 'custom', name: 'exec', description: 'Run JavaScript', format: { type: 'grammar' } }],
    input: [
      { type: 'custom_tool_call', id: 'ctc_previous', call_id: 'call_previous', name: 'exec', input: 'text(true)' },
      { type: 'custom_tool_call_output', call_id: 'call_previous', output: 'true' },
    ],
  })
  assert.deepEqual(prepared.customToolNames, ['exec'])
  assert.equal(prepared.payload.tools[0].type, 'function')
  assert.equal(prepared.payload.input[0].type, 'function_call')
  assert.match(prepared.payload.input[0].id, /^fc_/)
  assert.equal(JSON.parse(prepared.payload.input[0].arguments).input, 'text(true)')
  assert.equal(prepared.payload.input[1].type, 'function_call_output')

  const context = createProviderResponseContext(prepared.customToolNames)
  const added = sanitizeProviderResponse({
    type: 'response.output_item.added',
    output_index: 0,
    item: {
      type: 'function_call',
      id: 'foreign_fc_0',
      call_id: 'call_current',
      name: 'exec',
      arguments: JSON.stringify({ input: 'text("TOOLS_OK")' }),
      status: 'in_progress',
    },
  }, context)
  assert.equal(added.item.type, 'custom_tool_call')
  assert.match(added.item.id, /^ctc_/)
  assert.equal(added.item.input, 'text("TOOLS_OK")')

  const done = sanitizeProviderResponse({
    type: 'response.function_call_arguments.done',
    item_id: 'foreign_fc_0',
    output_index: 0,
    arguments: JSON.stringify({ input: 'text("TOOLS_OK")' }),
  }, context)
  assert.equal(done.type, 'response.custom_tool_call_input.done')
  assert.equal(done.item_id, added.item.id)
  assert.equal(done.input, 'text("TOOLS_OK")')
})

test('restores Codex local tools when the request already declares them as functions', () => {
  const prepared = prepareProviderRequest({
    model: 'third-party-model',
    tools: [
      {
        type: 'function',
        name: 'exec',
        description: 'Run JavaScript',
        parameters: {
          type: 'object',
          properties: { input: { type: 'string' } },
          required: ['input'],
          additionalProperties: false,
        },
      },
      {
        type: 'function',
        name: 'apply_patch',
        description: 'Apply a patch',
        parameters: {
          type: 'object',
          properties: { input: { type: 'string' } },
          required: ['input'],
          additionalProperties: false,
        },
      },
      {
        type: 'function',
        name: 'list_mcp_resources',
        description: 'List resources',
        parameters: { type: 'object', properties: {}, additionalProperties: false },
      },
    ],
  })

  assert.deepEqual(prepared.customToolNames, ['exec', 'apply_patch'])
  assert.equal(prepared.payload.tools[0].type, 'function')
  assert.equal(prepared.payload.tools[1].type, 'function')
  assert.equal(prepared.payload.tools[2].type, 'function')

  const context = createProviderResponseContext(prepared.customToolNames)
  const execAdded = sanitizeProviderResponse({
    type: 'response.output_item.added',
    item: {
      type: 'function_call',
      id: 'foreign_exec_fc',
      call_id: 'call_exec',
      name: 'exec',
      arguments: '',
      status: 'in_progress',
    },
  }, context)
  assert.equal(execAdded.item.type, 'custom_tool_call')
  assert.match(execAdded.item.id, /^ctc_/)

  sanitizeProviderResponse({
    type: 'response.function_call_arguments.delta',
    item_id: 'foreign_exec_fc',
    delta: '{"input":"text(\\"TOOLS_OK\\")"}',
  }, context)
  const execDone = sanitizeProviderResponse({
    type: 'response.function_call_arguments.done',
    item_id: 'foreign_exec_fc',
  }, context)
  assert.equal(execDone.type, 'response.custom_tool_call_input.done')
  assert.equal(execDone.item_id, execAdded.item.id)
  assert.equal(execDone.input, 'text("TOOLS_OK")')

  const patchDone = sanitizeProviderResponse({
    type: 'response.output_item.done',
    item: {
      type: 'function_call',
      id: 'foreign_patch_fc',
      call_id: 'call_patch',
      name: 'apply_patch',
      arguments: '{"input":"*** Begin Patch"}',
      status: 'completed',
    },
  }, context)
  assert.equal(patchDone.item.type, 'custom_tool_call')
  assert.equal(patchDone.item.input, '*** Begin Patch')

  const mcpDone = sanitizeProviderResponse({
    type: 'response.output_item.done',
    item: {
      type: 'function_call',
      id: 'foreign_mcp_fc',
      call_id: 'call_mcp',
      name: 'list_mcp_resources',
      arguments: '{}',
      status: 'completed',
    },
  }, context)
  assert.equal(mcpDone.item.type, 'function_call')
  assert.equal(mcpDone.item.name, 'list_mcp_resources')
})

test('buffers function argument deltas until complete custom input is available', () => {
  const context = createProviderResponseContext(['exec'])
  sanitizeProviderResponse({
    type: 'response.output_item.added',
    item: { type: 'function_call', id: 'foreign_fc_stream', name: 'exec', arguments: '' },
  }, context)
  for (const delta of ['{"input":"text(', '\\"STREAM_OK\\"', ')"}']) {
    const event = sanitizeProviderResponse({
      type: 'response.function_call_arguments.delta',
      item_id: 'foreign_fc_stream',
      delta,
    }, context)
    assert.equal(event.type, 'response.custom_tool_call_input.delta')
    assert.equal(event.delta, '')
  }
  const done = sanitizeProviderResponse({
    type: 'response.function_call_arguments.done',
    item_id: 'foreign_fc_stream',
  }, context)
  assert.equal(done.input, 'text("STREAM_OK")')
})

test('normalizes message ids and all streamed item references', () => {
  const context = createProviderResponseContext()
  const added = sanitizeProviderResponse({
    type: 'response.output_item.added',
    item: { type: 'message', id: 'foreign_msg_5', role: 'assistant', content: [] },
  }, context)
  const delta = sanitizeProviderResponse({
    type: 'response.output_text.delta',
    item_id: 'foreign_msg_5',
    delta: 'ok',
  }, context)
  assert.match(added.item.id, /^msg_/)
  assert.equal(delta.item_id, added.item.id)
})

test('normalizes third-party web search ids and streamed references', () => {
  const context = createProviderResponseContext()
  const added = sanitizeProviderResponse({
    type: 'response.output_item.added',
    item: { type: 'web_search_call', id: 'call_foreign_search', status: 'in_progress' },
  }, context)
  const completed = sanitizeProviderResponse({
    type: 'response.web_search_call.completed',
    item_id: 'call_foreign_search',
    output_index: 0,
  }, context)
  assert.match(added.item.id, /^ws_/)
  assert.equal(completed.item_id, added.item.id)
})

test('repairs persisted response item ids and later references across JSONL rows', () => {
  const aliases = new Map()
  const item = { type: 'response_item', payload: { type: 'message', id: 'foreign_msg_5' } }
  const repairedItem = repairResponseItemIds(item, aliases)
  const event = { type: 'event_msg', payload: { type: 'response.output_text.delta', item_id: 'foreign_msg_5' } }
  const repairedEvent = repairResponseItemIds(event, aliases)
  const webSearch = { type: 'response_item', payload: { type: 'web_search_call', id: 'call_foreign_search' } }
  const repairedWebSearch = repairResponseItemIds(webSearch, aliases)
  const webSearchEvent = {
    type: 'event_msg',
    payload: { type: 'response.web_search_call.completed', item_id: 'call_foreign_search' },
  }
  const repairedWebSearchEvent = repairResponseItemIds(webSearchEvent, aliases)
  assert.equal(repairedItem.repairedResponseItemIds, 1)
  assert.match(item.payload.id, /^msg_/)
  assert.equal(repairedEvent.repairedResponseItemIds, 1)
  assert.equal(event.payload.item_id, item.payload.id)
  assert.equal(repairedWebSearch.repairedResponseItemIds, 1)
  assert.match(webSearch.payload.id, /^ws_/)
  assert.equal(repairedWebSearchEvent.repairedResponseItemIds, 1)
  assert.equal(webSearchEvent.payload.item_id, webSearch.payload.id)
})
