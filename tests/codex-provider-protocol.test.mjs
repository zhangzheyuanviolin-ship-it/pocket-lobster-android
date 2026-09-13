import assert from 'node:assert/strict'
import test from 'node:test'
import {
  createProviderResponseContext,
  normalizeResponseItemId,
  prepareProviderRequest,
  repairResponseItemIds,
  sanitizeProviderResponse,
} from '../src/server/codexProviderProtocol.mjs'

test('normalizes foreign response item ids with stable Codex prefixes', () => {
  const first = normalizeResponseItemId('message', 'foreign_msg_5')
  assert.match(first, /^msg_/)
  assert.equal(first, normalizeResponseItemId('message', 'foreign_msg_5'))
  assert.equal(normalizeResponseItemId('message', 'msg_valid'), 'msg_valid')
  assert.match(normalizeResponseItemId('function_call', 'foreign_fc_0'), /^fc_/)
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

test('repairs persisted response item ids and later references across JSONL rows', () => {
  const aliases = new Map()
  const item = { type: 'response_item', payload: { type: 'message', id: 'foreign_msg_5' } }
  const repairedItem = repairResponseItemIds(item, aliases)
  const event = { type: 'event_msg', payload: { type: 'response.output_text.delta', item_id: 'foreign_msg_5' } }
  const repairedEvent = repairResponseItemIds(event, aliases)
  assert.equal(repairedItem.repairedResponseItemIds, 1)
  assert.match(item.payload.id, /^msg_/)
  assert.equal(repairedEvent.repairedResponseItemIds, 1)
  assert.equal(event.payload.item_id, item.payload.id)
})
