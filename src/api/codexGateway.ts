import {
  fetchRpcMethodCatalog,
  fetchRpcNotificationCatalog,
  fetchPendingServerRequests,
  rpcCall,
  respondServerRequest,
  subscribeRpcNotifications,
  type RpcNotification,
} from './codexRpcClient'
import type {
  ConfigReadResponse,
  ModelListResponse,
  ThreadListResponse,
  ThreadReadResponse,
} from './appServerDtos'
import { CodexApiError, extractErrorMessage, normalizeCodexApiError } from './codexErrors'
import { normalizeThreadGroupsV2, normalizeThreadMessagesV2 } from './normalizers/v2'
import type { CodexModelOption, ReasoningEffort, UiMessage, UiProjectGroup } from '../types/codex'

type CurrentModelConfig = {
  modelValue: string
  reasoningEffort: ReasoningEffort | ''
  signature: string
}

type StoredCodexModelConfig = {
  id?: string
  providerId?: string
  displayName?: string
  baseUrl?: string
  modelId?: string
  availableModelIds?: string[]
  supportedReasoningEfforts?: string[]
}

type ThreadRouteResponse = {
  providerId?: string
  model?: string
  sanitizedReasoningItems?: number
  thread?: { modelProvider?: string }
}

export type ThreadSnapshot = {
  messages: UiMessage[]
  latestTurnId: string
  latestTurnStatus: 'completed' | 'interrupted' | 'failed' | 'inProgress' | ''
  latestTurnError: string
}

type StoredCodexModelCatalog = {
  currentConfigId?: string
  configs?: StoredCodexModelConfig[]
}

const REASONING_EFFORTS: ReasoningEffort[] = [
  'none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra',
]

export function encodeModelValue(providerId: string, modelId: string): string {
  return `${encodeURIComponent(providerId)}::${encodeURIComponent(modelId)}`
}

export function decodeModelValue(value: string): { providerId: string; modelId: string } {
  const separator = value.indexOf('::')
  if (separator < 0) return { providerId: 'openai', modelId: value.trim() }
  try {
    return {
      providerId: decodeURIComponent(value.slice(0, separator)),
      modelId: decodeURIComponent(value.slice(separator + 2)),
    }
  } catch {
    return { providerId: 'openai', modelId: value.trim() }
  }
}

async function callRpc<T>(method: string, params?: unknown): Promise<T> {
  try {
    return await rpcCall<T>(method, params)
  } catch (error) {
    throw normalizeCodexApiError(error, `RPC ${method} failed`, method)
  }
}

function normalizeReasoningEffort(value: unknown): ReasoningEffort | '' {
  return typeof value === 'string' && REASONING_EFFORTS.includes(value as ReasoningEffort)
    ? (value as ReasoningEffort)
    : ''
}

function normalizeReasoningEfforts(values: unknown): ReasoningEffort[] {
  if (!Array.isArray(values)) return []
  const rows: ReasoningEffort[] = []
  for (const value of values) {
    const effort = typeof value === 'string'
      ? normalizeReasoningEffort(value)
      : normalizeReasoningEffort((value as { reasoningEffort?: unknown })?.reasoningEffort)
    if (effort && !rows.includes(effort)) rows.push(effort)
  }
  return rows
}

async function getStoredCodexModelCatalog(): Promise<StoredCodexModelCatalog> {
  try {
    const response = await fetch('/codex-api/model-providers', { cache: 'no-store' })
    if (!response.ok) return {}
    return await response.json() as StoredCodexModelCatalog
  } catch {
    return {}
  }
}

function inferProviderLabel(row: StoredCodexModelConfig): string {
  try {
    const host = new URL(row.baseUrl ?? '').hostname.toLowerCase()
    if (host === 'api.deepseek.com' || host.endsWith('.deepseek.com')) return 'DeepSeek'
    if (host.includes('openrouter')) return 'OpenRouter'
    if (host.includes('anthropic')) return 'Anthropic'
  } catch {
    // Fall back to the user-supplied display name.
  }
  const displayName = row.displayName?.trim() ?? ''
  const modelId = row.modelId?.trim() ?? ''
  return displayName && displayName !== modelId ? displayName.split(' / ')[0] || displayName : displayName || modelId
}

async function postCodexJson<T>(path: string, body: Record<string, unknown>): Promise<T> {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  const payload = await response.json().catch(() => null) as T | { error?: unknown } | null
  if (!response.ok) {
    throw new CodexApiError(
      extractErrorMessage(payload, `Codex request failed with HTTP ${response.status}`),
      { code: 'http_error', method: path, status: response.status },
    )
  }
  return payload as T
}

async function getThreadGroupsV2(): Promise<UiProjectGroup[]> {
  const payload = await callRpc<ThreadListResponse>('thread/list', {
    archived: false,
    limit: 100,
    sortKey: 'updated_at',
  })
  return normalizeThreadGroupsV2(payload)
}

async function getThreadSnapshotV2(threadId: string): Promise<ThreadSnapshot> {
  const payload = await callRpc<ThreadReadResponse>('thread/read', {
    threadId,
    includeTurns: true,
  })
  const turns = Array.isArray(payload.thread.turns) ? payload.thread.turns : []
  const latestTurn = turns[turns.length - 1]
  return {
    messages: normalizeThreadMessagesV2(payload),
    latestTurnId: latestTurn?.id ?? '',
    latestTurnStatus: latestTurn?.status ?? '',
    latestTurnError: latestTurn?.error?.message ?? '',
  }
}

export async function getThreadGroups(): Promise<UiProjectGroup[]> {
  try {
    return await getThreadGroupsV2()
  } catch (error) {
    throw normalizeCodexApiError(error, 'Failed to load thread groups', 'thread/list')
  }
}

export async function getThreadMessages(threadId: string): Promise<UiMessage[]> {
  return (await getThreadSnapshot(threadId)).messages
}

export async function getThreadSnapshot(threadId: string): Promise<ThreadSnapshot> {
  try {
    return await getThreadSnapshotV2(threadId)
  } catch (error) {
    throw normalizeCodexApiError(error, `Failed to load thread ${threadId}`, 'thread/read')
  }
}

export async function getMethodCatalog(): Promise<string[]> {
  return fetchRpcMethodCatalog()
}

export async function getNotificationCatalog(): Promise<string[]> {
  return fetchRpcNotificationCatalog()
}

export function subscribeCodexNotifications(onNotification: (value: RpcNotification) => void): () => void {
  return subscribeRpcNotifications(onNotification)
}

export type { RpcNotification }

export async function replyToServerRequest(
  id: number,
  payload: { result?: unknown; error?: { code?: number; message: string } },
): Promise<void> {
  await respondServerRequest({
    id,
    ...payload,
  })
}

export async function getPendingServerRequests(): Promise<unknown[]> {
  return fetchPendingServerRequests()
}

export async function resumeThread(threadId: string, model?: string, modelProvider?: string): Promise<void> {
  const params: Record<string, unknown> = { threadId }
  if (model?.trim()) params.model = model.trim()
  if (modelProvider?.trim()) params.modelProvider = modelProvider.trim()
  await callRpc('thread/resume', params)
}

export async function switchThreadRoute(
  threadId: string,
  model: string,
  providerId: string,
): Promise<ThreadRouteResponse> {
  return postCodexJson<ThreadRouteResponse>('/codex-api/thread-route', {
    threadId: threadId.trim(),
    model: model.trim(),
    providerId: providerId.trim() || 'openai',
  })
}

export async function getThreadRoute(threadId: string): Promise<{ providerId: string; model: string }> {
  const response = await fetch(`/codex-api/thread-route?threadId=${encodeURIComponent(threadId.trim())}`, {
    cache: 'no-store',
  })
  const payload = await response.json().catch(() => null) as { providerId?: unknown; model?: unknown; error?: unknown } | null
  if (!response.ok) {
    throw new CodexApiError(
      extractErrorMessage(payload, `Failed to read thread route with HTTP ${response.status}`),
      { code: 'http_error', method: 'thread-route', status: response.status },
    )
  }
  return {
    providerId: typeof payload?.providerId === 'string' ? payload.providerId.trim() || 'openai' : 'openai',
    model: typeof payload?.model === 'string' ? payload.model.trim() : '',
  }
}

export async function setActiveModelSelection(providerId: string, model: string): Promise<void> {
  await postCodexJson('/codex-api/model-selection', {
    providerId: providerId.trim() || 'openai',
    model: model.trim(),
  })
}

export async function unsubscribeThread(threadId: string): Promise<void> {
  await callRpc('thread/unsubscribe', { threadId })
}

export async function archiveThread(threadId: string): Promise<void> {
  await callRpc('thread/archive', { threadId })
}

export async function forkThread(threadId: string): Promise<string> {
  try {
    const payload = await callRpc<{ thread?: { id?: string } }>('thread/fork', {
      threadId,
      persistExtendedHistory: true,
    })
    const nextThreadId = normalizeThreadIdFromPayload(payload)
    if (!nextThreadId) {
      throw new Error('thread/fork did not return a thread id')
    }
    return nextThreadId
  } catch (error) {
    throw normalizeCodexApiError(error, `Failed to fork thread ${threadId}`, 'thread/fork')
  }
}

export async function rollbackThread(threadId: string, numTurns: number): Promise<void> {
  const normalizedThreadId = threadId.trim()
  if (!normalizedThreadId || !Number.isInteger(numTurns) || numTurns < 1) {
    return
  }
  try {
    await callRpc('thread/rollback', {
      threadId: normalizedThreadId,
      numTurns,
    })
  } catch (error) {
    throw normalizeCodexApiError(error, `Failed to rollback thread ${normalizedThreadId}`, 'thread/rollback')
  }
}

function normalizeThreadIdFromPayload(payload: unknown): string {
  if (!payload || typeof payload !== 'object') return ''
  const record = payload as Record<string, unknown>

  const thread = record.thread
  if (thread && typeof thread === 'object') {
    const threadId = (thread as Record<string, unknown>).id
    if (typeof threadId === 'string' && threadId.length > 0) {
      return threadId
    }
  }
  return ''
}

export async function startThread(
  cwd?: string,
  model?: string,
  modelProvider?: string,
): Promise<{ threadId: string; modelProvider: string }> {
  try {
    const params: Record<string, unknown> = {}
    if (typeof cwd === 'string' && cwd.trim().length > 0) {
      params.cwd = cwd.trim()
    }
    if (typeof model === 'string' && model.trim().length > 0) {
      params.model = model.trim()
    }
    if (typeof modelProvider === 'string' && modelProvider.trim().length > 0) {
      params.modelProvider = modelProvider.trim()
    }
    const payload = await callRpc<{ thread?: { id?: string; modelProvider?: string } }>('thread/start', params)
    const threadId = normalizeThreadIdFromPayload(payload)
    if (!threadId) {
      throw new Error('thread/start did not return a thread id')
    }
    return {
      threadId,
      modelProvider: payload.thread?.modelProvider?.trim() || modelProvider?.trim() || 'openai',
    }
  } catch (error) {
    throw normalizeCodexApiError(error, 'Failed to start a new thread', 'thread/start')
  }
}

export async function startThreadTurn(
  threadId: string,
  text: string,
  model?: string,
  effort?: ReasoningEffort,
): Promise<void> {
  try {
    const params: Record<string, unknown> = {
      threadId,
      input: [{ type: 'text', text }],
    }
    if (typeof model === 'string' && model.length > 0) {
      params.model = model
    }
    if (typeof effort === 'string' && effort.length > 0) {
      params.effort = effort
    }
    await callRpc('turn/start', params)
  } catch (error) {
    throw normalizeCodexApiError(error, `Failed to start turn for thread ${threadId}`, 'turn/start')
  }
}

export async function interruptThreadTurn(threadId: string, turnId?: string): Promise<void> {
  const normalizedThreadId = threadId.trim()
  const normalizedTurnId = turnId?.trim() || ''
  if (!normalizedThreadId) return

  try {
    if (!normalizedTurnId) {
      throw new Error('turn/interrupt requires turnId')
    }
    await callRpc('turn/interrupt', { threadId: normalizedThreadId, turnId: normalizedTurnId })
  } catch (error) {
    throw normalizeCodexApiError(error, `Failed to interrupt turn for thread ${normalizedThreadId}`, 'turn/interrupt')
  }
}

export async function setDefaultModel(model: string): Promise<void> {
  await callRpc('setDefaultModel', { model })
}

export async function getAvailableModels(): Promise<CodexModelOption[]> {
  const payload = await callRpc<ModelListResponse>('model/list', {})
  const models: CodexModelOption[] = []
  for (const row of payload.data) {
    const candidate = row.id || row.model
    if (!candidate) continue
    const value = encodeModelValue('openai', candidate)
    if (models.some((item) => item.value === value)) continue
    models.push({
      value,
      label: row.displayName || candidate,
      providerId: 'openai',
      providerLabel: 'OpenAI（ChatGPT 登录）',
      modelId: candidate,
      supportedReasoningEfforts: normalizeReasoningEfforts(row.supportedReasoningEfforts),
      defaultReasoningEffort: normalizeReasoningEffort(row.defaultReasoningEffort),
    })
  }
  const stored = await getStoredCodexModelCatalog()
  for (const row of stored.configs ?? []) {
    const providerId = row.providerId?.trim() ?? ''
    const configuredModel = row.modelId?.trim() ?? ''
    const modelIds = Array.from(new Set([
      configuredModel,
      ...(Array.isArray(row.availableModelIds) ? row.availableModelIds : []),
    ].map((value) => value?.trim()).filter((value): value is string => !!value)))
    if (!providerId || modelIds.length === 0) continue
    const efforts = normalizeReasoningEfforts(row.supportedReasoningEfforts)
    const providerLabel = inferProviderLabel(row)
    for (const modelId of modelIds) {
      const value = encodeModelValue(providerId, modelId)
      if (models.some((item) => item.value === value)) continue
      models.push({
        value,
        label: modelId,
        providerId,
        providerLabel,
        modelId,
        supportedReasoningEfforts: efforts,
        defaultReasoningEffort: efforts.includes('medium') ? 'medium' : efforts[0] ?? '',
      })
    }
  }
  return models
}

export async function getCurrentModelConfig(): Promise<CurrentModelConfig> {
  const [payload, stored] = await Promise.all([
    callRpc<ConfigReadResponse>('config/read', {}),
    getStoredCodexModelCatalog(),
  ])
  let providerId = payload.config.model_provider ?? ''
  let model = payload.config.model ?? ''
  if (!providerId || !model) {
    const current = (stored.configs ?? []).find((row) => row.id === stored.currentConfigId)
    providerId = providerId || current?.providerId?.trim() || 'openai'
    model = model || current?.modelId?.trim() || ''
  }
  const reasoningEffort = normalizeReasoningEffort(payload.config.model_reasoning_effort)
  const modelValue = model ? encodeModelValue(providerId || 'openai', model) : ''
  const signature = [stored.currentConfigId ?? '', providerId || 'openai', model, reasoningEffort].join('|')
  return { modelValue, reasoningEffort, signature }
}

// `thread/loaded/list` returns sessions loaded in memory, not currently running turns.
