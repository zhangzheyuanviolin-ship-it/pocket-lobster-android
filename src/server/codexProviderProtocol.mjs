import { createHash } from 'node:crypto'

const RESPONSE_ITEM_PREFIXES = new Map([
  ['message', 'msg'],
  ['reasoning', 'rs'],
  ['compaction', 'cmp'],
  ['function_call', 'fc'],
  ['custom_tool_call', 'ctc'],
])

function asRecord(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value : null
}

function stringValue(value) {
  return typeof value === 'string' ? value : ''
}

function customInputFromArguments(value) {
  const raw = stringValue(value)
  if (!raw) return ''
  try {
    const parsed = JSON.parse(raw)
    if (typeof parsed === 'string') return parsed
    const record = asRecord(parsed)
    if (record && typeof record.input === 'string') return record.input
  } catch {
    // Some compatible providers return the custom input directly.
  }
  return raw
}

function functionArgumentsFromCustomInput(value) {
  const input = typeof value === 'string' ? value : JSON.stringify(value ?? '')
  return JSON.stringify({ input })
}

export function normalizeResponseItemId(type, value) {
  const raw = stringValue(value)
  const prefix = RESPONSE_ITEM_PREFIXES.get(stringValue(type))
  if (!raw || !prefix || raw.startsWith(`${prefix}_`)) return raw
  const digest = createHash('sha256').update(`${type}\0${raw}`).digest('hex').slice(0, 32)
  return `${prefix}_${digest}`
}

export function repairResponseItemIds(value, itemIds = new Map()) {
  let repairedResponseItemIds = 0
  const visit = (node) => {
    if (Array.isArray(node)) {
      node.forEach(visit)
      return
    }
    const record = asRecord(node)
    if (!record) return
    const originalId = stringValue(record.id)
    const normalizedId = normalizeResponseItemId(record.type, originalId)
    if (originalId && normalizedId !== originalId) {
      itemIds.set(originalId, normalizedId)
      record.id = normalizedId
      repairedResponseItemIds += 1
    }
    Object.values(record).forEach(visit)
    const originalItemId = stringValue(record.item_id)
    const normalizedItemId = itemIds.get(originalItemId)
    if (normalizedItemId && normalizedItemId !== originalItemId) {
      record.item_id = normalizedItemId
      repairedResponseItemIds += 1
    }
  }
  visit(value)
  return { value, repairedResponseItemIds, itemIds }
}

function convertCustomToolDefinition(value) {
  const row = asRecord(value)
  if (!row || row.type !== 'custom' || typeof row.name !== 'string') return value
  return {
    type: 'function',
    name: row.name,
    description: stringValue(row.description),
    parameters: {
      type: 'object',
      properties: {
        input: {
          type: 'string',
          description: 'The complete plaintext input for this custom tool.',
        },
      },
      required: ['input'],
      additionalProperties: false,
    },
  }
}

function convertRequestNode(value, itemIds) {
  if (Array.isArray(value)) return value.map((child) => convertRequestNode(child, itemIds))
  const row = asRecord(value)
  if (!row) return value
  const converted = Object.fromEntries(
    Object.entries(row).map(([key, child]) => [key, convertRequestNode(child, itemIds)]),
  )
  if (converted.type === 'custom_tool_call') {
    converted.type = 'function_call'
    converted.arguments = functionArgumentsFromCustomInput(converted.input)
    delete converted.input
  } else if (converted.type === 'custom_tool_call_output') {
    converted.type = 'function_call_output'
  }
  const originalId = stringValue(converted.id)
  const normalizedId = normalizeResponseItemId(converted.type, originalId)
  if (originalId && normalizedId !== originalId) {
    itemIds.set(originalId, normalizedId)
    converted.id = normalizedId
  }
  const originalItemId = stringValue(converted.item_id)
  if (originalItemId && itemIds.has(originalItemId)) converted.item_id = itemIds.get(originalItemId)
  return converted
}

export function prepareProviderRequest(value) {
  const root = asRecord(value)
  if (!root) return { payload: value, customToolNames: [] }
  const customToolNames = (Array.isArray(root.tools) ? root.tools : [])
    .map(asRecord)
    .filter((tool) => tool?.type === 'custom' && typeof tool.name === 'string')
    .map((tool) => tool.name)
  const payload = convertRequestNode(value, new Map())
  const convertedRoot = asRecord(payload)
  if (convertedRoot && Array.isArray(root.tools)) {
    convertedRoot.tools = root.tools.map(convertCustomToolDefinition)
  }
  const choice = asRecord(convertedRoot?.tool_choice)
  if (choice?.type === 'custom' && typeof choice.name === 'string') {
    choice.type = 'function'
  }
  return { payload, customToolNames }
}

export function createProviderResponseContext(customToolNames = []) {
  return {
    customToolNames: new Set(customToolNames),
    itemIds: new Map(),
    customItemIds: new Set(),
    functionArgumentBuffers: new Map(),
  }
}

export function sanitizeProviderResponse(value, context) {
  if (Array.isArray(value)) return value.map((child) => sanitizeProviderResponse(child, context))
  const source = asRecord(value)
  if (!source) return value

  const result = Object.fromEntries(
    Object.entries(source).map(([key, child]) => [key, sanitizeProviderResponse(child, context)]),
  )
  const originalType = stringValue(result.type)
  const originalItemId = stringValue(result.item_id)
  const referencedCustomItem = originalItemId.length > 0 && (
    context.customItemIds.has(originalItemId) ||
    context.customItemIds.has(context.itemIds.get(originalItemId))
  )

  if (originalType === 'function_call' && context.customToolNames.has(stringValue(result.name))) {
    result.type = 'custom_tool_call'
    result.input = customInputFromArguments(result.arguments)
    delete result.arguments
  } else if (referencedCustomItem && originalType === 'response.function_call_arguments.delta') {
    result.type = 'response.custom_tool_call_input.delta'
    const buffered = `${context.functionArgumentBuffers.get(originalItemId) ?? ''}${stringValue(result.delta)}`
    context.functionArgumentBuffers.set(originalItemId, buffered)
    result.delta = ''
  } else if (referencedCustomItem && originalType === 'response.function_call_arguments.done') {
    result.type = 'response.custom_tool_call_input.done'
    result.input = customInputFromArguments(result.arguments ?? context.functionArgumentBuffers.get(originalItemId))
    delete result.arguments
    context.functionArgumentBuffers.delete(originalItemId)
  }

  const itemType = stringValue(result.type)
  const originalId = stringValue(result.id)
  const normalizedId = normalizeResponseItemId(itemType, originalId)
  if (originalId && normalizedId !== originalId) {
    context.itemIds.set(originalId, normalizedId)
    result.id = normalizedId
  }
  if (itemType === 'custom_tool_call') {
    if (originalId) context.customItemIds.add(originalId)
    if (normalizedId) context.customItemIds.add(normalizedId)
  }
  if (originalItemId && context.itemIds.has(originalItemId)) {
    result.item_id = context.itemIds.get(originalItemId)
  }
  return result
}
