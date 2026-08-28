import { spawn, type ChildProcess, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { accessSync, constants as fsConstants, createWriteStream, readFileSync, unlinkSync } from 'node:fs'
import { appendFile, mkdtemp, mkdir, readFile, readdir, rename, stat, unlink, writeFile } from 'node:fs/promises'
import type { IncomingMessage, ServerResponse } from 'node:http'
import { tmpdir } from 'node:os'
import { join, dirname, extname } from 'node:path'
import { handleCodexProviderAdapterRequest } from './codexProviderAdapter.js'

const prefixBin = process.env.PREFIX ? join(process.env.PREFIX, 'bin') : ''
const shellPath = prefixBin ? join(prefixBin, 'sh') : '/bin/sh'
const homeDir = process.env.HOME ?? ''
const promptInjectionPath = homeDir ? join(homeDir, '.openclaw-android', 'state', 'prompt-injection.json') : ''
const shizukuStatusPath = homeDir ? join(homeDir, '.openclaw-android', 'capabilities', 'shizuku.json') : ''
const offlineLinuxRuntimePath = homeDir
  ? join(homeDir, '.openclaw-android', 'state', 'offline-linux-runtime.json')
  : ''
const runtimeHealthPath = homeDir ? join(homeDir, '.openclaw-android', 'state', 'runtime-health.json') : ''
const codexModelProvidersPath = homeDir
  ? join(homeDir, '.openclaw-android', 'state', 'codex-model-providers.json')
  : ''
const codexProviderSecretsHandoffPath = homeDir
  ? join(homeDir, '.openclaw-android', 'state', 'codex-provider-secrets.handoff.json')
  : ''
const codexProviderRuntimeStatusPath = homeDir
  ? join(homeDir, '.openclaw-android', 'state', 'codex-provider-runtime-status.json')
  : ''
const codexSessionsPath = homeDir ? join(homeDir, '.codex', 'sessions') : ''
const codexChatDiagnosticPath = homeDir
  ? join(homeDir, '.openclaw-android', 'state', 'codex-chat-latest.jsonl')
  : ''
const codexChatSharedDiagnosticPath = process.env.ANYCLAW_EXPORT_DIR
  ? join(process.env.ANYCLAW_EXPORT_DIR, 'codex-chat-latest.jsonl')
  : ''
const CODEX_PROVIDER_SECRET_PREFIX = 'POCKET_LOBSTER_CODEX_'
const PROVIDER_MODEL_REFRESH_INTERVAL_MS = 5 * 60_000
const CODEX_DIAGNOSTIC_MAX_BYTES = 2 * 1024 * 1024
const CODEX_RPC_TIMEOUT_MS = 30_000
const CODEX_INITIALIZE_TIMEOUT_MS = 45_000
const CODEX_NOTIFICATION_HISTORY_LIMIT = 500
const OPENCLAW_UPLOAD_DIR = homeDir
  ? join(homeDir, '.openclaw', 'workspace', 'uploads')
  : join(process.cwd(), '.openclaw', 'workspace', 'uploads')
const OPENCLAW_UPLOAD_MAX_BYTES = 1_000_000_000
const OPENCLAW_UPLOAD_STREAM_MAX_BYTES = 1_000_000_000
const OPENCLAW_CONFIG_PATH = homeDir
  ? join(homeDir, '.openclaw', 'openclaw.json')
  : join(process.cwd(), '.openclaw', 'openclaw.json')
const OPENCLAW_WORKSPACE_DIR = homeDir
  ? join(homeDir, '.openclaw', 'workspace')
  : join(process.cwd(), '.openclaw', 'workspace')
const LIGHTWEIGHT_STATE_PATH = homeDir
  ? join(homeDir, '.openclaw-android', 'state', 'lightweight-openclaw-sessions.json')
  : join(process.cwd(), '.openclaw-android', 'state', 'lightweight-openclaw-sessions.json')
const LIGHTWEIGHT_MAX_CONTEXT_MESSAGES = 80
const LIGHTWEIGHT_MAX_TOOL_STEPS = 32
const LIGHTWEIGHT_COMMAND_TIMEOUT_MS = 180_000
const LIGHTWEIGHT_MODEL_REQUEST_TIMEOUT_MS = 95_000
const LIGHTWEIGHT_MODEL_REQUEST_MAX_RETRIES = 3
const LIGHTWEIGHT_RUN_HARD_TIMEOUT_MS = 15 * 60_000
const LIGHTWEIGHT_OUTPUT_LIMIT = 120_000
const LIGHTWEIGHT_BOOTSTRAP_TARGET_VERSION = '2026.3.2'
const LIGHTWEIGHT_DOC_MAX_CHARS = 4_000
const OPENCLAW_NATIVE_READY_CACHE_MS = 6_000
const OPENCLAW_GATEWAY_CALL_TIMEOUT_MS = 90_000
const OPENCLAW_HISTORY_CALL_TIMEOUT_MS = 75_000
const OPENCLAW_CHAT_SEND_TIMEOUT_MS = 45_000
const OPENCLAW_RUN_WAIT_TIMEOUT_MS = 12_000
const OPENCLAW_RUN_WAIT_GATEWAY_GRACE_MS = 8_000
const OPENCLAW_RUN_WAIT_ATTEMPTS = 2
const OPENCLAW_GATEWAY_CALL_MAX_RETRIES = 4
const OPENCLAW_GATEWAY_RETRY_BACKOFF_MS = [300, 700, 1200, 1800]
const OPENCLAW_NATIVE_STRICT_MODE = true
const OPENCLAW_BACKEND_MODE: 'lightweight_only' | 'native_gateway_only' = 'native_gateway_only'
const OPENCLAW_RUN_CONTEXT_TTL_MS = 6 * 60 * 60_000
const OPENCLAW_RUN_CONTEXT_MAX = 400
const OPENCLAW_HEARTBEAT_JOB_NAME = 'anyclaw-heartbeat-main'
const OPENCLAW_HEARTBEAT_PROMPT = 'Read HEARTBEAT.md if it exists (workspace context). Follow it strictly. Do not infer or repeat old tasks from prior chats. If nothing needs attention, reply HEARTBEAT_OK.'
const LIGHTWEIGHT_RUN_CONTEXT_TTL_MS = 2 * 60 * 60_000
const LIGHTWEIGHT_RUN_CONTEXT_MAX = 400
const CLAUDE_STATE_PATH = homeDir
  ? join(homeDir, '.pocketlobster', 'claude-web', 'sessions.json')
  : join(process.cwd(), '.pocketlobster', 'claude-web', 'sessions.json')
const CLAUDE_RUNS_STATE_PATH = homeDir
  ? join(homeDir, '.pocketlobster', 'claude-web', 'runs.json')
  : join(process.cwd(), '.pocketlobster', 'claude-web', 'runs.json')
const CLAUDE_UPLOAD_DIR = homeDir
  ? join(homeDir, '.pocketlobster', 'claude-web', 'uploads')
  : join(process.cwd(), '.pocketlobster', 'claude-web', 'uploads')
const CLAUDE_UPLOAD_MAX_BYTES = 1_000_000_000
const CLAUDE_UPLOAD_STREAM_MAX_BYTES = 1_000_000_000
const CLAUDE_RUN_WAIT_TIMEOUT_MS = 12_000
const CLAUDE_RUN_CONTEXT_TTL_MS = 2 * 60 * 60_000
const CLAUDE_PROCESS_LINES_MAX = 240
const CLAUDE_OUTPUT_CHARS_MAX = 240_000
const CLAUDE_SESSION_HISTORY_DEFAULT = 60
const CLAUDE_NO_OUTPUT_WARN_MS = 25_000
const COLLABORATION_STATE_PATH = homeDir
  ? join(homeDir, '.pocketlobster', 'collaboration', 'runs.json')
  : join(process.cwd(), '.pocketlobster', 'collaboration', 'runs.json')
const COLLABORATION_SHARED_ROOT = normalizeText(process.env.POCKET_LOBSTER_COLLABORATION_SHARED_ROOT)
  || '/sdcard/下载管理/口袋大龙虾协作'
const SHARED_BRIDGE_TOKEN_PATH = homeDir
  ? join(dirname(homeDir), 'shared-runtime', 'bridge-token')
  : ''
const MINIS_BRIDGE_URL = 'http://127.0.0.1:18927'
const COLLABORATION_TIMEOUT_MS = 2 * 60 * 60_000
const COLLABORATION_COORDINATOR_TIMEOUT_MS = 10 * 60_000
const COLLABORATION_MAX_DELEGATION_ROUNDS = 3
const COLLABORATION_HISTORY_LIMIT = 40
const SERVER_BUNDLE_ID = (() => {
  try {
    const entryPath = process.argv[1] || ''
    if (entryPath) {
      const embedded = readFileSync(join(dirname(entryPath), '..', 'bundle-id'), 'utf8').trim()
      if (embedded) return embedded
    }
  } catch {
    // Development builds can use the environment fallback before Android assets are assembled.
  }
  return normalizeText(process.env.POCKET_LOBSTER_SERVER_BUNDLE_ID)
})()

type JsonRpcCall = {
  jsonrpc: '2.0'
  id: number
  method: string
  params?: unknown
}

function reloadCodexProviderSecretEnvironment(): number {
  if (!codexProviderSecretsHandoffPath) return 0
  let parsed: unknown
  try {
    parsed = JSON.parse(readFileSync(codexProviderSecretsHandoffPath, 'utf8')) as unknown
  } catch {
    return 0
  } finally {
    try {
      unlinkSync(codexProviderSecretsHandoffPath)
    } catch {
      // The handoff may already have been consumed by another startup path.
    }
  }

  const values = asRecord(parsed)
  if (!values) return 0
  for (const key of Object.keys(process.env)) {
    if (key.startsWith(CODEX_PROVIDER_SECRET_PREFIX) && key.endsWith('_API_KEY')) {
      delete process.env[key]
    }
  }
  let loaded = 0
  for (const [key, value] of Object.entries(values)) {
    if (!/^POCKET_LOBSTER_CODEX_[A-Z0-9_]+_API_KEY$/u.test(key)) continue
    if (typeof value !== 'string' || value.trim().length === 0) continue
    process.env[key] = value.trim()
    loaded += 1
  }
  return loaded
}

type JsonRpcResponse = {
  id?: number
  result?: unknown
  error?: {
    code: number
    message: string
  }
  method?: string
  params?: unknown
}

type RpcProxyRequest = {
  method: string
  params?: unknown
}

type ServerRequestReply = {
  result?: unknown
  error?: {
    code: number
    message: string
  }
}

type PendingServerRequest = {
  id: number
  method: string
  params: unknown
  receivedAtIso: string
}

type SequencedNotification = {
  sequence: number
  method: string
  params: unknown
}

type LightweightContentItem = {
  type: string
  text?: string
  thinking?: string
  name?: string
  arguments?: unknown
}

type LightweightHistoryMessage = {
  role: string
  timestamp: number
  content: LightweightContentItem[]
  toolName?: string
  isError?: boolean
}

type LightweightSession = {
  key: string
  title: string
  updatedAt: number
  lastMessagePreview: string
  modelProvider: string
  model: string
  messages: LightweightHistoryMessage[]
}

type LightweightState = {
  sessions: LightweightSession[]
}

type LightweightRunContext = {
  runId: string
  sessionKey: string
  status: string
  startedAtMs: number
  updatedAtMs: number
  completed: boolean
  errorText: string
}

type LightweightModelConfig = {
  modelId: string
  modelName: string
  providerName: string
  baseUrl: string
  apiKey: string
}

type OpenClawNativeRunContext = {
  runId: string
  sessionKey: string
  sentAtMs: number
  lastStatus: string
  lastError: string
  updatedAtMs: number
}

type ClaudeContentItem = {
  type: string
  text?: string
}

type ClaudeHistoryMessage = {
  role: string
  timestamp: number
  content: ClaudeContentItem[]
  toolName?: string
  isError?: boolean
}

type ClaudeSession = {
  key: string
  title: string
  updatedAt: number
  lastMessagePreview: string
  modelProvider: string
  model: string
  messages: ClaudeHistoryMessage[]
}

type ClaudeState = {
  sessions: ClaudeSession[]
}

type ClaudeModelConfig = {
  providerName: string
  modelId: string
  baseUrl: string
  apiKey: string
}

type ClaudeRunContext = {
  runId: string
  sessionKey: string
  status: string
  startedAtMs: number
  updatedAtMs: number
  lastOutputAtMs: number
  completed: boolean
  processLines: string[]
  rawOutput: string
  assistantText: string
  errorText: string
  exitCode: number | null
  process: ChildProcess | null
}

type ClaudePersistedRun = {
  runId: string
  sessionKey: string
  status: string
  startedAtMs: number
  updatedAtMs: number
  lastOutputAtMs: number
  completed: boolean
  processLines: string[]
  assistantText: string
  errorText: string
  exitCode: number | null
}

type CollaborationAgentId = 'codex' | 'claude' | 'minis'
type CollaborationAgentStatus = 'idle' | 'pending' | 'planning' | 'running' | 'reviewing' | 'waiting_user' | 'completed' | 'failed' | 'aborted'
type CollaborationRunStatus = 'planning' | 'running' | 'reviewing' | 'waiting_user' | 'completed' | 'failed' | 'aborted'
type CollaborationEventType = 'user' | 'decision' | 'assignment' | 'progress' | 'result' | 'final' | 'error' | 'control'

type CollaborationEvent = {
  id: string
  atMs: number
  type: CollaborationEventType
  agentId: CollaborationAgentId | ''
  text: string
}

type CollaborationAssignment = {
  agentId: CollaborationAgentId
  task: string
  expectedOutput: string
}

type CollaborationDecision = {
  action: 'respond' | 'ask_user' | 'delegate'
  message: string
  assignments: CollaborationAssignment[]
  requiresSharedWorkspace: boolean
}

type CollaborationAgentState = {
  agentId: CollaborationAgentId
  role: 'leader' | 'worker'
  status: CollaborationAgentStatus
  sessionId: string
  runId: string
  turnId: string
  startedAtMs: number
  updatedAtMs: number
  requestText: string
  assignmentText: string
  actionText: string
  responseText: string
  errorText: string
}

type CollaborationRun = {
  id: string
  title: string
  prompt: string
  leader: CollaborationAgentId
  status: CollaborationRunStatus
  createdAtMs: number
  updatedAtMs: number
  completedAtMs: number | null
  turnNumber: number
  roundNumber: number
  archived: boolean
  workspaceReady: boolean
  pendingUserMessages: string[]
  events: CollaborationEvent[]
  agents: Record<CollaborationAgentId, CollaborationAgentState>
  finalSummary: string
  errorText: string
}

let openClawNativeReadyCacheValue: boolean | null = null
let openClawNativeReadyCacheAtMs = 0
const openClawNativeRuns = new Map<string, OpenClawNativeRunContext>()
const lightweightRuns = new Map<string, LightweightRunContext>()
const lightweightRunAbortRequested = new Set<string>()
const claudeRuns = new Map<string, ClaudeRunContext>()
let claudeRunsPersistTimer: NodeJS.Timeout | null = null
const collaborationRuns = new Map<string, CollaborationRun>()
let collaborationRunsLoaded = false
let collaborationPersistTimer: NodeJS.Timeout | null = null
let collaborationPersistQueue: Promise<void> = Promise.resolve()

function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null
}

function getErrorMessage(payload: unknown, fallback: string): string {
  if (payload instanceof Error && payload.message.trim().length > 0) {
    return payload.message
  }

  const record = asRecord(payload)
  if (!record) return fallback

  const error = record.error
  if (typeof error === 'string' && error.length > 0) return error

  const nestedError = asRecord(error)
  if (nestedError && typeof nestedError.message === 'string' && nestedError.message.length > 0) {
    return nestedError.message
  }

  return fallback
}

function isMissingAgentSessionError(error: unknown, agentId: 'codex' | 'minis'): boolean {
  const message = getErrorMessage(error, '').toLowerCase()
  return agentId === 'codex'
    ? message.includes('thread not found') || message.includes('persisted thread not found')
    : message.includes('session not found')
}

function setJson(res: ServerResponse, statusCode: number, payload: unknown): void {
  res.statusCode = statusCode
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  res.end(JSON.stringify(payload))
}

async function readJsonBody(req: IncomingMessage): Promise<unknown> {
  const chunks: Uint8Array[] = []

  for await (const chunk of req) {
    chunks.push(typeof chunk === 'string' ? Buffer.from(chunk) : chunk)
  }

  if (chunks.length === 0) return null

  const raw = Buffer.concat(chunks).toString('utf8').trim()
  if (raw.length === 0) return null

  return JSON.parse(raw) as unknown
}

async function readJsonFile(path: string): Promise<Record<string, unknown> | null> {
  if (!path) return null
  try {
    const raw = await readFile(path, 'utf8')
    const parsed = JSON.parse(raw) as unknown
    return asRecord(parsed)
  } catch {
    return null
  }
}

async function writeJsonFileAtomic(path: string, value: unknown): Promise<void> {
  await writeTextFileAtomic(path, `${JSON.stringify(value, null, 2)}\n`)
}

async function writeTextFileAtomic(path: string, value: string): Promise<void> {
  await mkdir(dirname(path), { recursive: true })
  const temp = `${path}.${process.pid}.tmp`
  await writeFile(temp, value, { encoding: 'utf8', mode: 0o600 })
  await rename(temp, path)
}

let codexDiagnosticWriteChain: Promise<void> = Promise.resolve()
let codexSharedDiagnosticWriteChain: Promise<void> = Promise.resolve()

async function appendDiagnosticLine(path: string, line: string): Promise<void> {
  await mkdir(dirname(path), { recursive: true })
  try {
    const info = await stat(path)
    if (info.size >= CODEX_DIAGNOSTIC_MAX_BYTES) {
      const previous = path.replace(/\.jsonl$/u, '-previous.jsonl')
      try { await unlink(previous) } catch { /* No previous diagnostic file. */ }
      await rename(path, previous)
    }
  } catch {
    // The file is created by appendFile on the first event.
  }
  await appendFile(path, line, { encoding: 'utf8', mode: 0o600 })
}

async function appendCodexDiagnostic(event: string, details: Record<string, unknown> = {}): Promise<void> {
  if (!codexChatDiagnosticPath) return
  const line = `${JSON.stringify({
    at: new Date().toISOString(),
    event,
    ...details,
  })}\n`
  codexDiagnosticWriteChain = codexDiagnosticWriteChain.catch(() => undefined).then(async () => {
    await appendDiagnosticLine(codexChatDiagnosticPath, line)
  }).catch(() => undefined)
  await codexDiagnosticWriteChain

  if (codexChatSharedDiagnosticPath) {
    codexSharedDiagnosticWriteChain = codexSharedDiagnosticWriteChain.catch(() => undefined).then(async () => {
    try {
      await appendDiagnosticLine(codexChatSharedDiagnosticPath, line)
    } catch {
      const command = `mkdir -p ${shellQuote(dirname(codexChatSharedDiagnosticPath))} && printf %s ${shellQuote(line)} >> ${shellQuote(codexChatSharedDiagnosticPath)}`
      await runSystemShellCommand(command)
    }
    }).catch(() => undefined)
  }
}

function diagnosticRpcFields(method: string, params: unknown, result?: unknown): Record<string, unknown> {
  const input = asRecord(params)
  const output = asRecord(result)
  const inputThread = normalizeText(input?.threadId)
  const outputThread = asRecord(output?.thread)
  const turns = Array.isArray(outputThread?.turns) ? outputThread.turns : []
  const latestTurn = asRecord(turns.at(-1))
  return {
    method,
    threadId: inputThread || normalizeText(outputThread?.id),
    providerId: normalizeText(input?.modelProvider) || normalizeText(outputThread?.modelProvider),
    model: normalizeText(input?.model),
    inputItems: Array.isArray(input?.input) ? input.input.length : 0,
    turnCount: turns.length,
    latestTurnId: normalizeText(latestTurn?.id),
    latestTurnStatus: normalizeText(latestTurn?.status),
    latestItemCount: Array.isArray(latestTurn?.items) ? latestTurn.items.length : 0,
  }
}

async function findThreadRolloutPath(directory: string, threadId: string): Promise<string> {
  if (!directory || !threadId) return ''
  let entries
  try {
    entries = await readdir(directory, { withFileTypes: true })
  } catch {
    return ''
  }
  for (const entry of entries) {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) {
      const nested = await findThreadRolloutPath(path, threadId)
      if (nested) return nested
    } else if (entry.isFile() && entry.name.endsWith('.jsonl') && entry.name.includes(threadId)) {
      return path
    }
  }
  return ''
}

type PersistedThreadRouteMigration = {
  path: string
  original: string
  changed: boolean
  sanitizedReasoningItems: number
  removedCompactionItems: number
}

async function migratePersistedThreadRoute(
  threadId: string,
  providerId: string,
): Promise<PersistedThreadRouteMigration> {
  const path = await findThreadRolloutPath(codexSessionsPath, threadId)
  if (!path) throw new Error(`Persisted thread not found: ${threadId}`)
  const raw = await readFile(path, 'utf8')
  let providerMetadataFound = false
  let changed = false
  let sanitizedReasoningItems = 0
  let removedCompactionItems = 0
  const lines = raw.split('\n').flatMap((line) => {
    if (!line.trim()) return line
    try {
      const row = asRecord(JSON.parse(line) as unknown)
      if (!row) return line
      const payload = asRecord(row.payload)
      let lineChanged = false
      if (row.type === 'session_meta' && payload) {
        providerMetadataFound = true
        if (normalizeText(payload.model_provider) !== providerId) {
          payload.model_provider = providerId
          changed = true
          lineChanged = true
        }
      }
      if (row.type === 'response_item' && normalizeText(payload?.type) === 'reasoning') {
        sanitizedReasoningItems += 1
        changed = true
        return []
      }
      if (row.type === 'response_item' && normalizeText(payload?.type) === 'compaction') {
        removedCompactionItems += 1
        changed = true
        return []
      }
      return lineChanged ? JSON.stringify(row) : line
    } catch {
      return line
    }
  })
  if (!providerMetadataFound) throw new Error(`Thread provider metadata not found: ${threadId}`)
  if (changed) await writeTextFileAtomic(path, lines.join('\n'))
  return { path, original: raw, changed, sanitizedReasoningItems, removedCompactionItems }
}

async function restorePersistedThreadRoute(migration: PersistedThreadRouteMigration): Promise<void> {
  if (migration.changed) await writeTextFileAtomic(migration.path, migration.original)
}

async function readPersistedThreadModel(threadId: string): Promise<string> {
  const path = await findThreadRolloutPath(codexSessionsPath, threadId)
  if (!path) return ''
  const lines = (await readFile(path, 'utf8')).split('\n')
  for (let index = lines.length - 1; index >= 0; index--) {
    const line = lines[index]
    if (!line?.trim()) continue
    try {
      const row = asRecord(JSON.parse(line) as unknown)
      if (row?.type !== 'turn_context') continue
      const model = normalizeText(asRecord(row.payload)?.model)
      if (model) return model
    } catch {
      // Ignore malformed trailing lines and continue to the previous turn context.
    }
  }
  return ''
}

function normalizeText(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

function shellQuote(value: string): string {
  return `'${value.replace(/'/gu, `'\"'\"'`)}'`
}

function readHeaderText(value: string | string[] | undefined): string {
  if (Array.isArray(value)) {
    return typeof value[0] === 'string' ? value[0].trim() : ''
  }
  return typeof value === 'string' ? value.trim() : ''
}

function clampInt(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min
  if (value < min) return min
  if (value > max) return max
  return Math.floor(value)
}

function normalizeTimeoutMs(value: unknown, fallback: number, min: number, max: number): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return clampInt(fallback, min, max)
  }
  return clampInt(value, min, max)
}

function sleepMs(delayMs: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, Math.max(0, delayMs)))
}

function rememberOpenClawNativeReady(value: boolean): void {
  openClawNativeReadyCacheValue = value
  openClawNativeReadyCacheAtMs = Date.now()
}

function invalidateOpenClawNativeReadyCache(): void {
  openClawNativeReadyCacheValue = null
  openClawNativeReadyCacheAtMs = 0
}

function isOpenClawGatewayRetryableError(message: string): boolean {
  const normalized = message.toLowerCase()
  return normalized.includes('gateway closed (1006') ||
    normalized.includes('abnormal closure') ||
    normalized.includes('connection is not open') ||
    normalized.includes('openclaw gateway call timeout') ||
    normalized.includes('timed out') ||
    normalized.includes('econnreset') ||
    normalized.includes('socket hang up') ||
    normalized.includes('no json payload found') ||
    normalized.includes('empty gateway response')
}

function tryParseJsonPayload(raw: string): boolean {
  try {
    JSON.parse(raw)
    return true
  } catch {
    return false
  }
}

function extractBalancedJsonSegments(raw: string): string[] {
  const segments: string[] = []
  for (let start = 0; start < raw.length; start += 1) {
    const first = raw[start]
    if (first !== '{' && first !== '[') continue
    const stack: string[] = [first === '{' ? '}' : ']']
    let inString = false
    let escaped = false
    for (let index = start + 1; index < raw.length; index += 1) {
      const char = raw[index]
      if (inString) {
        if (escaped) {
          escaped = false
          continue
        }
        if (char === '\\') {
          escaped = true
          continue
        }
        if (char === '"') {
          inString = false
        }
        continue
      }
      if (char === '"') {
        inString = true
        continue
      }
      if (char === '{') {
        stack.push('}')
        continue
      }
      if (char === '[') {
        stack.push(']')
        continue
      }
      const expected = stack[stack.length - 1]
      if ((char === '}' || char === ']') && char === expected) {
        stack.pop()
        if (stack.length === 0) {
          const segment = raw.slice(start, index + 1).trim()
          if (segment.length > 1) {
            segments.push(segment)
          }
          break
        }
      }
    }
  }
  return segments
}

function extractJsonPayload(raw: string): string {
  const trimmed = raw.trim()
  if (!trimmed) {
    throw new Error('Empty gateway response')
  }
  if (tryParseJsonPayload(trimmed)) {
    return trimmed
  }

  const lineCandidates: string[] = []
  const lines = trimmed.split(/\r?\n/)
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index].trimStart()
    if (!line.startsWith('{') && !line.startsWith('[')) continue
    const candidate = lines.slice(index).join('\n').trim()
    if (candidate) lineCandidates.push(candidate)
  }

  const balancedCandidates = extractBalancedJsonSegments(trimmed)
  for (const candidate of [...lineCandidates, ...balancedCandidates]) {
    if (tryParseJsonPayload(candidate)) {
      return candidate
    }
  }

  throw new Error('No JSON payload found in gateway response')
}

async function runOpenClawGatewayCall(
  method: string,
  params: unknown,
  timeoutMs = OPENCLAW_GATEWAY_CALL_TIMEOUT_MS,
): Promise<unknown> {
  const normalizedMethod = method.trim()
  if (!/^[a-zA-Z0-9._/-]+$/u.test(normalizedMethod)) {
    throw new Error(`Invalid OpenClaw gateway method: ${method}`)
  }

  const serializedParams = JSON.stringify(params ?? {})
  const command =
    `openclaw gateway call ${normalizedMethod} --json --params ${shellQuote(serializedParams)}`

  const runCommandOnce = () =>
    new Promise<string>((resolve, reject) => {
      const env = { ...process.env }
      if (prefixBin) {
        const currentPath = typeof env.PATH === 'string' ? env.PATH : ''
        if (!currentPath.split(':').includes(prefixBin)) {
          env.PATH = currentPath.length > 0 ? `${prefixBin}:${currentPath}` : prefixBin
        }
      }

      const child = spawn(shellPath, ['-c', command], {
        stdio: ['ignore', 'pipe', 'pipe'],
        env,
        cwd: homeDir || process.cwd(),
      })

      let stdoutBuffer = ''
      let stderrBuffer = ''
      let settled = false
      child.stdout.setEncoding('utf8')
      child.stderr.setEncoding('utf8')
      const timer = setTimeout(() => {
        if (settled) return
        settled = true
        try {
          child.kill('SIGKILL')
        } catch {
          // ignore kill errors
        }
        reject(new Error(`OpenClaw gateway call timeout: ${normalizedMethod} (${timeoutMs}ms)`))
      }, timeoutMs)

      child.stdout.on('data', (chunk: string) => {
        stdoutBuffer += chunk
      })
      child.stderr.on('data', (chunk: string) => {
        stderrBuffer += chunk
      })

      child.on('error', (error) => {
        if (settled) return
        settled = true
        clearTimeout(timer)
        reject(error)
      })
      child.on('exit', (code) => {
        if (settled) return
        settled = true
        clearTimeout(timer)
        if (code === 0) {
          const output = stdoutBuffer.trim().length > 0 ? stdoutBuffer : stderrBuffer
          resolve(output)
          return
        }
        const errorText = stderrBuffer.trim() || stdoutBuffer.trim() || `OpenClaw gateway call failed: ${normalizedMethod}`
        reject(new Error(errorText))
      })
    })

  let lastError: unknown = null
  for (let attempt = 0; attempt < OPENCLAW_GATEWAY_CALL_MAX_RETRIES; attempt += 1) {
    try {
      const output = await runCommandOnce()
      const payload = extractJsonPayload(output)
      return JSON.parse(payload) as unknown
    } catch (error) {
      lastError = error
      const message = getErrorMessage(error, '')
      const retryable = isOpenClawGatewayRetryableError(message)
      if (!retryable || attempt >= OPENCLAW_GATEWAY_CALL_MAX_RETRIES - 1) {
        throw error
      }
      invalidateOpenClawNativeReadyCache()
      const delayMs = OPENCLAW_GATEWAY_RETRY_BACKOFF_MS[Math.min(attempt, OPENCLAW_GATEWAY_RETRY_BACKOFF_MS.length - 1)]
      await sleepMs(delayMs)
    }
  }

  throw (lastError instanceof Error ? lastError : new Error(`OpenClaw gateway call failed: ${normalizedMethod}`))
}

async function tryRunOpenClawGatewayCall(method: string, params: unknown): Promise<unknown | null> {
  try {
    return await runOpenClawGatewayCall(method, params)
  } catch {
    return null
  }
}

async function isNativeOpenClawReady(forceRefresh = false): Promise<boolean> {
  if (isOpenClawLightweightOnlyMode()) {
    return false
  }
  const now = Date.now()
  if (
    !forceRefresh &&
    openClawNativeReadyCacheValue !== null &&
    now - openClawNativeReadyCacheAtMs < OPENCLAW_NATIVE_READY_CACHE_MS
  ) {
    return openClawNativeReadyCacheValue
  }

  const ready = (await tryRunOpenClawGatewayCall('health', {})) !== null
  rememberOpenClawNativeReady(ready)
  return ready
}

const OPENCLAW_COMPLETED_RUN_STATUSES = new Set([
  'ok',
  'completed',
  'failed',
  'error',
  'cancelled',
  'canceled',
  'aborted',
])

function pruneOpenClawNativeRuns(nowMs = Date.now()): void {
  const cutoff = nowMs - OPENCLAW_RUN_CONTEXT_TTL_MS
  for (const [runId, context] of openClawNativeRuns.entries()) {
    if (context.updatedAtMs < cutoff) {
      openClawNativeRuns.delete(runId)
    }
  }
  if (openClawNativeRuns.size <= OPENCLAW_RUN_CONTEXT_MAX) return
  const sorted = [...openClawNativeRuns.values()].sort((first, second) => first.updatedAtMs - second.updatedAtMs)
  const overflow = openClawNativeRuns.size - OPENCLAW_RUN_CONTEXT_MAX
  for (let index = 0; index < overflow; index += 1) {
    openClawNativeRuns.delete(sorted[index].runId)
  }
}

function rememberOpenClawNativeRun(runId: string, sessionKey: string): void {
  const normalizedRunId = runId.trim()
  const normalizedSessionKey = sessionKey.trim()
  if (!normalizedRunId || !normalizedSessionKey) return
  const nowMs = Date.now()
  openClawNativeRuns.set(normalizedRunId, {
    runId: normalizedRunId,
    sessionKey: normalizedSessionKey,
    sentAtMs: nowMs,
    lastStatus: 'submitted',
    lastError: '',
    updatedAtMs: nowMs,
  })
  pruneOpenClawNativeRuns(nowMs)
}

function updateOpenClawNativeRun(runId: string, status: string, errorText = ''): void {
  const normalizedRunId = runId.trim()
  if (!normalizedRunId) return
  const nowMs = Date.now()
  const existing = openClawNativeRuns.get(normalizedRunId)
  if (!existing) return
  existing.lastStatus = status.trim() || existing.lastStatus
  existing.lastError = errorText.trim()
  existing.updatedAtMs = nowMs
  if (OPENCLAW_COMPLETED_RUN_STATUSES.has(existing.lastStatus)) {
    openClawNativeRuns.delete(normalizedRunId)
  } else {
    openClawNativeRuns.set(normalizedRunId, existing)
  }
  pruneOpenClawNativeRuns(nowMs)
}

function clearOpenClawNativeRun(runId: string): void {
  const normalizedRunId = runId.trim()
  if (!normalizedRunId) return
  openClawNativeRuns.delete(normalizedRunId)
}

function extractCronJobId(row: Record<string, unknown>): string {
  const candidates = [
    row.id,
    row.jobId,
    row.key,
  ]
  for (const candidate of candidates) {
    const normalized = normalizeText(candidate)
    if (normalized) return normalized
  }
  return ''
}

function findHeartbeatCronJob(cronListResult: unknown): {
  id: string
  name: string
} | null {
  const record = asRecord(cronListResult) ?? {}
  const rows = Array.isArray(record.jobs) ? record.jobs : []
  for (const row of rows) {
    const item = asRecord(row)
    if (!item) continue
    const name = normalizeText(item.name)
    const id = extractCronJobId(item)
    if (!id) continue
    if (name === OPENCLAW_HEARTBEAT_JOB_NAME) {
      return { id, name }
    }
  }
  for (const row of rows) {
    const item = asRecord(row)
    if (!item) continue
    const name = normalizeText(item.name).toLowerCase()
    const id = extractCronJobId(item)
    if (!id) continue
    if (name.includes('heartbeat')) {
      return {
        id,
        name: normalizeText(item.name) || OPENCLAW_HEARTBEAT_JOB_NAME,
      }
    }
  }
  return null
}

function isLikelyAssistantResultContent(content: unknown): boolean {
  if (!Array.isArray(content)) return false
  for (const row of content) {
    const item = asRecord(row)
    if (!item) continue
    const type = normalizeText(item.type)
    if (type === 'text' && normalizeText(item.text).length > 0) return true
    if (type === 'image') return true
  }
  return false
}

async function probeOpenClawRunCompletionByHistory(runId: string): Promise<{
  completed: boolean
  status: string
  result: unknown
  error: unknown
} | null> {
  const context = openClawNativeRuns.get(runId.trim())
  if (!context) return null
  try {
    const nativeHistory = await runOpenClawGatewayCall(
      'chat.history',
      {
        sessionKey: context.sessionKey,
        limit: 120,
      },
      OPENCLAW_HISTORY_CALL_TIMEOUT_MS,
    )
    const record = asRecord(nativeHistory) ?? {}
    const rows = Array.isArray(record.messages) ? record.messages : []
    for (let index = rows.length - 1; index >= 0; index -= 1) {
      const row = asRecord(rows[index]) ?? {}
      const timestamp = typeof row.timestamp === 'number' && Number.isFinite(row.timestamp)
        ? row.timestamp
        : 0
      if (timestamp < context.sentAtMs) continue
      const role = normalizeText(row.role)
      if (role === 'assistant' && isLikelyAssistantResultContent(row.content)) {
        return {
          completed: true,
          status: 'completed',
          result: {
            source: 'history-probe',
            sessionKey: context.sessionKey,
          },
          error: null,
        }
      }
      if (role === 'toolResult' && row.isError === true) {
        return {
          completed: true,
          status: 'failed',
          result: null,
          error: row.content ?? 'toolResult error',
        }
      }
    }
    return {
      completed: false,
      status: 'running',
      result: null,
      error: null,
    }
  } catch {
    return null
  }
}

function setOpenClawNativeUnavailable(
  res: ServerResponse,
  statusCode: number,
  error: string,
  code: string,
): void {
  setJson(res, statusCode, {
    ok: false,
    error,
    code,
    retryable: true,
    fallbackDisabled: OPENCLAW_NATIVE_STRICT_MODE,
  })
}

function readPositiveInt(value: string | null, fallback: number): number {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) return fallback
  const normalized = Math.floor(parsed)
  if (normalized < 1) return fallback
  return normalized
}

function isOpenClawLightweightOnlyMode(): boolean {
  return OPENCLAW_BACKEND_MODE === 'lightweight_only'
}

function pruneLightweightRuns(nowMs = Date.now()): void {
  const cutoff = nowMs - LIGHTWEIGHT_RUN_CONTEXT_TTL_MS
  for (const [runId, context] of lightweightRuns.entries()) {
    if (context.updatedAtMs < cutoff) {
      lightweightRuns.delete(runId)
      lightweightRunAbortRequested.delete(runId)
    }
  }
  if (lightweightRuns.size <= LIGHTWEIGHT_RUN_CONTEXT_MAX) return
  const sorted = [...lightweightRuns.values()].sort((first, second) => first.updatedAtMs - second.updatedAtMs)
  const overflow = lightweightRuns.size - LIGHTWEIGHT_RUN_CONTEXT_MAX
  for (let index = 0; index < overflow; index += 1) {
    const runId = sorted[index].runId
    lightweightRuns.delete(runId)
    lightweightRunAbortRequested.delete(runId)
  }
}

function rememberLightweightRun(runId: string, sessionKey: string): LightweightRunContext {
  const nowMs = Date.now()
  const context: LightweightRunContext = {
    runId,
    sessionKey,
    status: 'running',
    startedAtMs: nowMs,
    updatedAtMs: nowMs,
    completed: false,
    errorText: '',
  }
  lightweightRuns.set(runId, context)
  pruneLightweightRuns(nowMs)
  return context
}

function updateLightweightRun(runId: string, status: string, completed: boolean, errorText = ''): void {
  const existing = lightweightRuns.get(runId)
  if (!existing) return
  existing.status = status.trim() || existing.status
  existing.completed = completed
  existing.errorText = errorText.trim()
  existing.updatedAtMs = Date.now()
  lightweightRuns.set(runId, existing)
  if (completed) {
    lightweightRunAbortRequested.delete(runId)
  }
  pruneLightweightRuns(existing.updatedAtMs)
}

function isLightweightRunAbortRequested(runId: string): boolean {
  return lightweightRunAbortRequested.has(runId)
}

function findLatestRunningLightweightRun(sessionKey: string): string {
  let picked: LightweightRunContext | null = null
  for (const run of lightweightRuns.values()) {
    if (run.sessionKey !== sessionKey || run.completed) continue
    if (!picked || run.updatedAtMs > picked.updatedAtMs) {
      picked = run
    }
  }
  return picked?.runId ?? ''
}

function toLightweightRunWaitPayload(runId: string): Record<string, unknown> {
  const original = lightweightRuns.get(runId)
  if (!original) {
    return {
      ok: false,
      runId,
      status: 'unknown',
      completed: false,
      retryable: true,
      source: 'lightweight',
      error: 'Run state unavailable, retry wait or refresh history',
    }
  }
  if (
    !original.completed &&
    Date.now() - original.startedAtMs > LIGHTWEIGHT_RUN_HARD_TIMEOUT_MS
  ) {
    updateLightweightRun(
      runId,
      'failed',
      true,
      `任务执行超时（>${Math.floor(LIGHTWEIGHT_RUN_HARD_TIMEOUT_MS / 1000)}秒）`,
    )
  }
  const context = lightweightRuns.get(runId) ?? original
  return {
    ok: context.completed ? context.status === 'completed' : true,
    runId,
    status: context.status,
    completed: context.completed,
    retryable: !context.completed,
    source: 'lightweight',
    result: context.completed ? { source: 'lightweight', sessionKey: context.sessionKey } : null,
    error: context.errorText || null,
  }
}

function toOpenClawSessionSummary(row: Record<string, unknown>): Record<string, unknown> | null {
  const key = normalizeText(row.key)
  if (!key) return null

  const updatedAt =
    typeof row.updatedAt === 'number' && Number.isFinite(row.updatedAt)
      ? row.updatedAt
      : Date.now()

  return {
    key,
    displayName:
      normalizeText(row.displayName) ||
      normalizeText(row.label) ||
      normalizeText(row.derivedTitle) ||
      key,
    label: normalizeText(row.label) || normalizeText(row.displayName) || '',
    updatedAt,
    lastMessagePreview: normalizeText(row.lastMessagePreview),
    modelProvider: normalizeText(row.modelProvider),
    model: normalizeText(row.model),
  }
}

function truncateText(value: string, limit: number): string {
  if (value.length <= limit) return value
  return `${value.slice(0, limit)}\n\n[output truncated]`
}

async function readOptionalTextFile(path: string, limit = LIGHTWEIGHT_DOC_MAX_CHARS): Promise<string> {
  try {
    const raw = await readFile(path, 'utf8')
    const trimmed = raw.trim()
    if (!trimmed) return ''
    return truncateText(trimmed, limit)
  } catch {
    return ''
  }
}

async function buildLightweightWorkspaceContext(): Promise<string> {
  const docs = [
    { title: 'AGENTS.md', path: join(OPENCLAW_WORKSPACE_DIR, 'AGENTS.md') },
    { title: 'SOUL.md', path: join(OPENCLAW_WORKSPACE_DIR, 'SOUL.md') },
    { title: 'TOOLS.md', path: join(OPENCLAW_WORKSPACE_DIR, 'TOOLS.md') },
    { title: 'HEARTBEAT.md', path: join(OPENCLAW_WORKSPACE_DIR, 'HEARTBEAT.md') },
  ]
  const chunks: string[] = []
  for (const doc of docs) {
    const text = await readOptionalTextFile(doc.path)
    if (!text) continue
    chunks.push(`${doc.title}:\n${text}`)
  }
  return chunks.join('\n\n').trim()
}

function toTextContent(text: string): LightweightContentItem[] {
  return [{ type: 'text', text }]
}

function toThinkingContent(text: string): LightweightContentItem[] {
  return [{ type: 'thinking', thinking: text }]
}

function extractMessageText(message: LightweightHistoryMessage): string {
  const chunks: string[] = []
  for (const item of message.content) {
    if (item.type === 'text' && typeof item.text === 'string' && item.text.trim().length > 0) {
      chunks.push(item.text.trim())
    }
  }
  return chunks.join('\n\n').trim()
}

function buildSessionPreview(messages: LightweightHistoryMessage[]): string {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const text = extractMessageText(messages[index])
    if (text.length > 0) {
      return text.slice(0, 120)
    }
  }
  return ''
}

function createDefaultLightweightState(): LightweightState {
  return { sessions: [] }
}

async function readLightweightState(): Promise<LightweightState> {
  try {
    const raw = await readFile(LIGHTWEIGHT_STATE_PATH, 'utf8')
    const parsed = JSON.parse(raw) as unknown
    const record = asRecord(parsed)
    const rows = Array.isArray(record?.sessions) ? record.sessions : []
    const sessions: LightweightSession[] = []
    for (const row of rows) {
      const item = asRecord(row)
      if (!item) continue
      const key = normalizeText(item.key)
      if (!key) continue
      const title = normalizeText(item.title) || normalizeText(item.label) || key
      const updatedAt = typeof item.updatedAt === 'number' && Number.isFinite(item.updatedAt)
        ? item.updatedAt
        : Date.now()
      const modelProvider = normalizeText(item.modelProvider)
      const model = normalizeText(item.model)
      const messagesRaw = Array.isArray(item.messages) ? item.messages : []
      const messages: LightweightHistoryMessage[] = []
      for (const msgRow of messagesRaw) {
        const msg = asRecord(msgRow)
        if (!msg) continue
        const role = normalizeText(msg.role)
        if (!role) continue
        const timestamp = typeof msg.timestamp === 'number' && Number.isFinite(msg.timestamp)
          ? msg.timestamp
          : Date.now()
        const contentRows = Array.isArray(msg.content) ? msg.content : []
        const content: LightweightContentItem[] = []
        for (const c of contentRows) {
          const itemRow = asRecord(c)
          if (!itemRow) continue
          const type = normalizeText(itemRow.type)
          if (!type) continue
          const text = normalizeText(itemRow.text)
          const thinking = normalizeText(itemRow.thinking)
          const name = normalizeText(itemRow.name)
          content.push({
            type,
            text: text || undefined,
            thinking: thinking || undefined,
            name: name || undefined,
            arguments: itemRow.arguments,
          })
        }
        messages.push({
          role,
          timestamp,
          content,
          toolName: normalizeText(msg.toolName) || undefined,
          isError: msg.isError === true,
        })
      }
      sessions.push({
        key,
        title,
        updatedAt,
        lastMessagePreview: normalizeText(item.lastMessagePreview),
        modelProvider,
        model,
        messages,
      })
    }
    return { sessions }
  } catch {
    return createDefaultLightweightState()
  }
}

async function writeLightweightState(state: LightweightState): Promise<void> {
  await mkdir(dirname(LIGHTWEIGHT_STATE_PATH), { recursive: true })
  await writeFile(LIGHTWEIGHT_STATE_PATH, JSON.stringify(state, null, 2), 'utf8')
}

function buildDefaultLightweightSession(key = ''): LightweightSession {
  const sessionKey = key.trim() || `agent:main:light-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
  const now = Date.now()
  return {
    key: sessionKey,
    title: '新会话',
    updatedAt: now,
    lastMessagePreview: '',
    modelProvider: '',
    model: '',
    messages: [],
  }
}

async function ensureLightweightSession(
  state: LightweightState,
  preferredKey: string,
): Promise<LightweightSession> {
  const normalized = preferredKey.trim()
  if (normalized.length > 0) {
    const existing = state.sessions.find((row) => row.key === normalized)
    if (existing) return existing
    const created = buildDefaultLightweightSession(normalized)
    state.sessions.push(created)
    await writeLightweightState(state)
    return created
  }
  if (state.sessions.length > 0) {
    const sorted = [...state.sessions].sort((a, b) => b.updatedAt - a.updatedAt)
    return sorted[0]
  }
  const created = buildDefaultLightweightSession()
  state.sessions.push(created)
  await writeLightweightState(state)
  return created
}

function normalizeBaseUrl(baseUrl: string): string {
  let normalized = baseUrl.trim()
  if (!normalized) return ''
  if (normalized.endsWith('/chat/completions')) {
    normalized = normalized.slice(0, -'/chat/completions'.length)
  }
  if (normalized.endsWith('/responses')) {
    normalized = normalized.slice(0, -'/responses'.length)
  }
  return normalized.replace(/\/+$/gu, '')
}

function parseProviderModelIds(providerName: string, providerConfig: Record<string, unknown>): string[] {
  const ids = new Set<string>()
  const directModel = normalizeText(providerConfig.model)
  if (directModel) {
    ids.add(directModel.includes('/') ? directModel : `${providerName}/${directModel}`)
  }

  const modelsValue = providerConfig.models
  if (Array.isArray(modelsValue)) {
    for (const row of modelsValue) {
      const rowRecord = asRecord(row)
      const rawId = rowRecord ? normalizeText(rowRecord.id) : normalizeText(row)
      if (!rawId) continue
      ids.add(rawId.includes('/') ? rawId : `${providerName}/${rawId}`)
    }
  }

  return [...ids]
}

function normalizeModelIdForProvider(providerName: string, modelId: string): string {
  const trimmed = modelId.trim()
  if (!trimmed) return ''
  if (trimmed.includes('/')) return trimmed
  return `${providerName}/${trimmed}`
}

async function readLightweightModelConfig(): Promise<LightweightModelConfig> {
  const raw = await readFile(OPENCLAW_CONFIG_PATH, 'utf8')
  const parsed = JSON.parse(raw) as unknown
  const root = asRecord(parsed)
  if (!root) {
    throw new Error('OpenClaw config is missing or invalid')
  }

  const agents = asRecord(root.agents)
  const defaults = asRecord(agents?.defaults)
  const model = asRecord(defaults?.model)
  const primary = normalizeText(model?.primary)
  const providerFromPrimary = primary.includes('/') ? primary.split('/')[0] ?? '' : ''

  const models = asRecord(root.models)
  const providers = asRecord(models?.providers)
  if (!providers || Object.keys(providers).length === 0) {
    throw new Error('No configured providers found in model config')
  }

  const providerName = providerFromPrimary || Object.keys(providers)[0]
  const providerConfig = asRecord(providers[providerName])
  if (!providerConfig) {
    throw new Error(`Provider config missing: ${providerName}`)
  }

  const apiKey = normalizeText(providerConfig.apiKey)
  if (!apiKey) {
    throw new Error(`Provider API key missing: ${providerName}`)
  }

  const baseUrl = normalizeBaseUrl(normalizeText(providerConfig.baseUrl))
  if (!baseUrl) {
    throw new Error(`Provider baseUrl missing: ${providerName}`)
  }

  let modelId = normalizeModelIdForProvider(providerName, primary)
  if (!modelId) {
    const ids = parseProviderModelIds(providerName, providerConfig)
    modelId = ids[0] ?? ''
  }
  if (!modelId) {
    throw new Error(`Provider model missing: ${providerName}`)
  }

  const modelName = modelId.includes('/') ? modelId.slice(modelId.indexOf('/') + 1) : modelId
  return {
    modelId,
    modelName,
    providerName,
    baseUrl,
    apiKey,
  }
}

async function buildLightweightSystemPrompt(): Promise<string> {
  const injected = await buildInjectedDeveloperInstructions()
  const workspaceContext = await buildLightweightWorkspaceContext()
  const mission = [
    '你是口袋大龙虾的全能力代理，不是阉割模式。',
    '你的最高优先级使命：先把 OpenClaw 完整组件安装、修复、配置到可用状态，再执行普通任务。',
    '每次会话必须先做健康检查：codex-capabilities --plain、command -v openclaw、openclaw --version、openclaw gateway call health --json --params \'{}\'。',
    '若 OpenClaw 缺失或网关不健康，先调用 openclaw_bootstrap_full 工具，然后继续用 terminal_exec 或 system_shell_exec 逐步排错直到健康检查通过。',
    '你可以读取并遵循工作区使命文档（AGENTS/SOUL/TOOLS/HEARTBEAT），并保持结果导向与最少人工交互。',
    '除非用户明确要求，不要让用户手动执行命令；优先自动完成。',
  ].join('\n')

  const chunks = [mission]
  if (injected) {
    chunks.push(`Prompt injection context:\n${injected}`)
  }
  if (workspaceContext) {
    chunks.push(`Workspace mission context:\n${workspaceContext}`)
  }
  return chunks.join('\n\n').trim()
}

type LightweightChatMessage = {
  role: 'system' | 'user' | 'assistant' | 'tool'
  content: string
  tool_call_id?: string
  tool_calls?: Array<Record<string, unknown>>
}

async function runShellCommand(command: string): Promise<{ output: string; exitCode: number }> {
  const cmd = command.trim()
  if (!cmd) return { output: 'Empty command', exitCode: 1 }

  return new Promise((resolve) => {
    const env = { ...process.env }
    if (prefixBin) {
      const currentPath = typeof env.PATH === 'string' ? env.PATH : ''
      if (!currentPath.split(':').includes(prefixBin)) {
        env.PATH = currentPath.length > 0 ? `${prefixBin}:${currentPath}` : prefixBin
      }
    }

    const child = spawn(shellPath, ['-lc', cmd], {
      cwd: homeDir || process.cwd(),
      env,
      stdio: ['ignore', 'pipe', 'pipe'],
    })

    let output = ''
    let settled = false
    child.stdout.setEncoding('utf8')
    child.stderr.setEncoding('utf8')
    child.stdout.on('data', (chunk: string) => {
      output += chunk
    })
    child.stderr.on('data', (chunk: string) => {
      output += chunk
    })

    const timer = setTimeout(() => {
      if (settled) return
      settled = true
      child.kill('SIGKILL')
      const body = truncateText(output.trim(), LIGHTWEIGHT_OUTPUT_LIMIT)
      resolve({
        output: body ? `${body}\n\n[timeout after ${Math.floor(LIGHTWEIGHT_COMMAND_TIMEOUT_MS / 1000)}s]` : '[timeout]',
        exitCode: 124,
      })
    }, LIGHTWEIGHT_COMMAND_TIMEOUT_MS)

    child.on('error', (error) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      resolve({ output: `command launch failed: ${error.message}`, exitCode: 127 })
    })

    child.on('exit', (code) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      const text = truncateText(output.trim(), LIGHTWEIGHT_OUTPUT_LIMIT)
      resolve({
        output: text || '[no output]',
        exitCode: typeof code === 'number' ? code : 0,
      })
    })
  })
}

function isUbuntuRuntimeCommand(command: string): boolean {
  const normalized = command.trim().toLowerCase()
  if (!normalized) return false
  return normalized.includes('ubuntu-shell') ||
    normalized.includes('ubuntu-status') ||
    normalized.includes('.openclaw-android/linux-runtime/bin/ubuntu-shell.sh') ||
    normalized.includes('anyclaw_ubuntu_bin')
}

async function runSystemShellCommand(command: string): Promise<{ output: string; exitCode: number }> {
  const cmd = command.trim()
  if (!cmd) return { output: 'Empty command', exitCode: 1 }
  if (isUbuntuRuntimeCommand(cmd)) {
    return runShellCommand(cmd)
  }
  return runShellCommand(`system-shell ${shellQuote(cmd)}`)
}

async function runOpenClawBootstrapRecipe(): Promise<{ output: string; exitCode: number }> {
  const npmCli = prefixBin
    ? join(dirname(prefixBin), 'lib', 'node_modules', 'npm', 'bin', 'npm-cli.js')
    : '$PREFIX/lib/node_modules/npm/bin/npm-cli.js'
  const steps: Array<{ name: string; command: string }> = [
    {
      name: 'capability-and-path-preflight',
      command: [
        'set +e',
        'echo "HOME=$HOME"',
        'echo "PREFIX=$PREFIX"',
        'command -v node || true',
        'command -v npm || true',
        'command -v pkg || true',
        'command -v openclaw || true',
      ].join('\n'),
    },
    {
      name: 'toolchain-prerequisites',
      command: [
        'set +e',
        'pkg install -y git tar xz-utils python clang make cmake binutils lld >/dev/null 2>&1 || true',
        'apt-get update --allow-insecure-repositories >/dev/null 2>&1 || true',
        'apt-get install -y --allow-unauthenticated git tar xz-utils >/dev/null 2>&1 || true',
      ].join('\n'),
    },
    {
      name: 'install-openclaw-npm',
      command: [
        'set +e',
        `NPM_CLI="${npmCli}"`,
        'if [ ! -f "$NPM_CLI" ]; then',
        '  echo "npm-cli-missing:$NPM_CLI"',
        '  exit 21',
        'fi',
        'node "$NPM_CLI" config set registry https://registry.npmjs.org >/dev/null 2>&1 || true',
        'node "$NPM_CLI" cache clean --force >/dev/null 2>&1 || true',
        `node "$NPM_CLI" install -g --ignore-scripts --force openclaw@${LIGHTWEIGHT_BOOTSTRAP_TARGET_VERSION} 2>&1`,
      ].join('\n'),
    },
    {
      name: 'ensure-openclaw-wrapper',
      command: [
        'set +e',
        'if [ -f "$PREFIX/lib/node_modules/openclaw/openclaw.mjs" ]; then',
        '  cat > "$PREFIX/bin/openclaw" <<EOF',
        '#!/system/bin/sh',
        'exec $PREFIX/bin/node $PREFIX/lib/node_modules/openclaw/openclaw.mjs "$@"',
        'EOF',
        '  chmod 700 "$PREFIX/bin/openclaw"',
        'fi',
        'command -v openclaw || true',
      ].join('\n'),
    },
    {
      name: 'verify-openclaw-health',
      command: [
        'set +e',
        'openclaw --version 2>&1 || true',
        'openclaw gateway call health --json --params \'{}\' 2>&1 || true',
      ].join('\n'),
    },
  ]

  const logs: string[] = []
  let hardFailure = false
  for (const step of steps) {
    const result = await runShellCommand(step.command)
    logs.push(`### ${step.name} (exit=${result.exitCode})`)
    logs.push(result.output)
    logs.push('')
    if (step.name === 'install-openclaw-npm' && result.exitCode !== 0) {
      hardFailure = true
    }
  }

  const verify = await runShellCommand('command -v openclaw >/dev/null 2>&1 && openclaw --version >/dev/null 2>&1')
  if (verify.exitCode !== 0) {
    hardFailure = true
    logs.push('### final-check')
    logs.push('openclaw command is still unavailable after bootstrap recipe')
  }

  return {
    output: truncateText(logs.join('\n'), LIGHTWEIGHT_OUTPUT_LIMIT),
    exitCode: hardFailure ? 1 : 0,
  }
}

function buildLightweightContextMessages(
  session: LightweightSession,
): LightweightChatMessage[] {
  const contextRows = session.messages.slice(-LIGHTWEIGHT_MAX_CONTEXT_MESSAGES)
  const output: LightweightChatMessage[] = []
  for (const row of contextRows) {
    if (row.role === 'user') {
      const text = extractMessageText(row)
      if (!text) continue
      output.push({ role: 'user', content: text })
      continue
    }
    if (row.role === 'assistant') {
      const text = extractMessageText(row)
      if (!text) continue
      output.push({ role: 'assistant', content: text })
      continue
    }
  }
  return output
}

function stringifyToolResult(command: string, result: { output: string; exitCode: number }): string {
  return [
    `command: ${command}`,
    `exit_code: ${result.exitCode}`,
    'output:',
    result.output,
  ].join('\n')
}

async function callProviderChatCompletions(
  model: LightweightModelConfig,
  messages: LightweightChatMessage[],
): Promise<Record<string, unknown>> {
  const endpoint = `${model.baseUrl}/chat/completions`
  const payload: Record<string, unknown> = {
    model: model.modelName,
    messages,
    stream: false,
    tools: [
      {
        type: 'function',
        function: {
          name: 'terminal_exec',
          description: 'Execute a shell command on local Android terminal and return stdout/stderr.',
          parameters: {
            type: 'object',
            properties: {
              command: {
                type: 'string',
                description: 'Shell command to execute',
              },
            },
            required: ['command'],
            additionalProperties: false,
          },
        },
      },
      {
        type: 'function',
        function: {
          name: 'system_shell_exec',
          description: 'Execute Android system-level shell command via system-shell bridge.',
          parameters: {
            type: 'object',
            properties: {
              command: {
                type: 'string',
                description: 'System-level shell command to execute',
              },
            },
            required: ['command'],
            additionalProperties: false,
          },
        },
      },
      {
        type: 'function',
        function: {
          name: 'openclaw_bootstrap_full',
          description: 'Run full OpenClaw bootstrap/repair recipe (npm install, wrapper fix, health probes).',
          parameters: {
            type: 'object',
            properties: {
              reason: {
                type: 'string',
                description: 'Why bootstrap is requested',
              },
            },
            additionalProperties: false,
          },
        },
      },
    ],
    tool_choice: 'auto',
  }

  let lastError: unknown = null
  for (let attempt = 0; attempt < LIGHTWEIGHT_MODEL_REQUEST_MAX_RETRIES; attempt += 1) {
    const controller = typeof AbortController !== 'undefined' ? new AbortController() : null
    const timer = controller
      ? setTimeout(() => controller.abort(), LIGHTWEIGHT_MODEL_REQUEST_TIMEOUT_MS)
      : null
    try {
      const response = await fetch(endpoint, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${model.apiKey}`,
        },
        body: JSON.stringify(payload),
        signal: controller?.signal,
      })

      const raw = await response.text()
      let parsed: unknown = null
      try {
        parsed = raw ? JSON.parse(raw) : null
      } catch {
        parsed = null
      }

      if (!response.ok) {
        const record = asRecord(parsed)
        const errorMessage = normalizeText(record?.error) ||
          normalizeText(asRecord(record?.error)?.message) ||
          `HTTP ${response.status}`
        throw new Error(`Model request failed: ${errorMessage}`)
      }

      return asRecord(parsed) ?? {}
    } catch (error) {
      lastError = error
      const message = getErrorMessage(error, 'Model request failed')
      const normalized = message.toLowerCase()
      const isTimeoutAbort = error instanceof DOMException && error.name === 'AbortError'
      const retryable = isTimeoutAbort ||
        normalized.includes('timeout') ||
        normalized.includes('timed out') ||
        normalized.includes('failed to fetch') ||
        normalized.includes('network') ||
        normalized.includes('socket') ||
        normalized.includes('econnreset') ||
        normalized.includes('503') ||
        normalized.includes('429')
      if (attempt < LIGHTWEIGHT_MODEL_REQUEST_MAX_RETRIES - 1 && retryable) {
        await sleepMs(350 + attempt * 450)
        continue
      }
      if (isTimeoutAbort) {
        throw new Error(`Model request timeout after ${Math.floor(LIGHTWEIGHT_MODEL_REQUEST_TIMEOUT_MS / 1000)}s`)
      }
      throw (error instanceof Error ? error : new Error(message))
    } finally {
      if (timer) {
        clearTimeout(timer)
      }
    }
  }

  throw (lastError instanceof Error ? lastError : new Error('Model request failed'))
}

async function runLightweightTurn(
  session: LightweightSession,
  options?: {
    isAborted?: () => boolean
  },
): Promise<void> {
  const model = await readLightweightModelConfig()
  session.model = model.modelId
  session.modelProvider = model.providerName

  const systemPrompt = await buildLightweightSystemPrompt()
  const contextMessages = buildLightweightContextMessages(session)
  const requestMessages: LightweightChatMessage[] = [
    { role: 'system', content: systemPrompt },
    ...contextMessages,
  ]

  let finalAssistantText = ''
  for (let step = 0; step < LIGHTWEIGHT_MAX_TOOL_STEPS; step += 1) {
    if (options?.isAborted?.()) {
      throw new Error('lightweight-run-aborted')
    }
    const envelope = await callProviderChatCompletions(model, requestMessages)
    const choices = Array.isArray(envelope.choices) ? envelope.choices : []
    const firstChoice = asRecord(choices[0])
    const message = asRecord(firstChoice?.message)
    if (!message) {
      throw new Error('Model response missing message')
    }

    const textContent = normalizeText(message.content)
    const toolCalls = Array.isArray(message.tool_calls) ? message.tool_calls : []

    if (toolCalls.length === 0) {
      finalAssistantText = textContent || '任务已完成。'
      break
    }

    const assistantToolItems: LightweightContentItem[] = []
    requestMessages.push({
      role: 'assistant',
      content: textContent,
      tool_calls: toolCalls.map((row) => asRecord(row) ?? {}),
    })

    for (const call of toolCalls) {
      if (options?.isAborted?.()) {
        throw new Error('lightweight-run-aborted')
      }
      const callRecord = asRecord(call)
      const callId = normalizeText(callRecord?.id) || randomUUID()
      const fn = asRecord(callRecord?.function)
      const fnName = normalizeText(fn?.name)
      const fnArgsRaw = normalizeText(fn?.arguments)
      let command = ''
      let result: { output: string; exitCode: number }
      try {
        const args = JSON.parse(fnArgsRaw || '{}') as unknown
        command = normalizeText(asRecord(args)?.command)
      } catch {
        command = ''
      }

      if (fnName === 'terminal_exec') {
        assistantToolItems.push({
          type: 'toolCall',
          name: 'terminal_exec',
          arguments: { command },
        })
        result = await runShellCommand(command)
      } else if (fnName === 'system_shell_exec') {
        assistantToolItems.push({
          type: 'toolCall',
          name: 'system_shell_exec',
          arguments: { command },
        })
        result = await runSystemShellCommand(command)
      } else if (fnName === 'openclaw_bootstrap_full') {
        assistantToolItems.push({
          type: 'toolCall',
          name: 'openclaw_bootstrap_full',
          arguments: { reason: command || 'runtime bootstrap request' },
        })
        result = await runOpenClawBootstrapRecipe()
      } else {
        continue
      }

      const rendered = stringifyToolResult(
        fnName === 'openclaw_bootstrap_full' ? fnName : (command || fnName),
        result,
      )
      session.messages.push({
        role: 'toolResult',
        timestamp: Date.now(),
        toolName: fnName,
        isError: result.exitCode !== 0,
        content: toTextContent(rendered),
      })
      requestMessages.push({
        role: 'tool',
        tool_call_id: callId,
        content: rendered,
      })
    }

    if (assistantToolItems.length > 0) {
      session.messages.push({
        role: 'assistant',
        timestamp: Date.now(),
        content: assistantToolItems,
      })
    }
  }

  if (!finalAssistantText) {
    finalAssistantText = '任务执行结束。'
  }
  session.messages.push({
    role: 'assistant',
    timestamp: Date.now(),
    content: toTextContent(finalAssistantText),
  })
}

function sanitizeOpenClawUploadFileName(fileName: string): string {
  const trimmed = fileName.trim()
  const base = trimmed.length > 0 ? trimmed : 'attachment.bin'
  const collapsed = base
    .replace(/[/\\]/gu, '_')
    .replace(/[^\p{L}\p{N}._-]/gu, '_')
    .replace(/_+/gu, '_')
    .slice(0, 120)
  return collapsed.length > 0 ? collapsed : 'attachment.bin'
}

function normalizeOpenClawUploadExtension(fileName: string): string {
  const extension = extname(fileName.trim()).toLowerCase()
  if (!extension) return '.bin'
  if (extension.length > 12) return '.bin'
  if (!/^\.[a-z0-9]+$/u.test(extension)) return '.bin'
  return extension
}

function buildOpenClawUploadStoredName(fileName: string): string {
  const stamp = Date.now().toString(36)
  const rand = Math.random().toString(36).slice(2, 8)
  const extension = normalizeOpenClawUploadExtension(fileName)
  return 'att-' + stamp + rand + extension
}


async function writeOpenClawUploadStream(
  req: IncomingMessage,
  storedPath: string,
  maxBytes: number,
): Promise<number> {
  const writer = createWriteStream(storedPath, {
    flags: 'wx',
    mode: 0o600,
  })

  let sizeBytes = 0
  try {
    for await (const chunk of req) {
      const buffer = typeof chunk === 'string' ? Buffer.from(chunk) : chunk
      sizeBytes += buffer.length
      if (sizeBytes > maxBytes) {
        throw new Error(`Attachment exceeds size limit (${Math.floor(maxBytes / 1_000_000)}MB)`)
      }
      if (!writer.write(buffer)) {
        await new Promise<void>((resolve, reject) => {
          writer.once('drain', resolve)
          writer.once('error', reject)
        })
      }
    }

    await new Promise<void>((resolve, reject) => {
      writer.end((error?: Error | null) => {
        if (error) {
          reject(error)
          return
        }
        resolve()
      })
    })
    return sizeBytes
  } catch (error) {
    writer.destroy()
    throw error
  }
}

function decodeStrictBase64(value: string): Buffer {
  const normalized = value.replace(/\s+/gu, '').trim()
  if (!normalized || normalized.length % 4 !== 0 || !/^[A-Za-z0-9+/]+={0,2}$/u.test(normalized)) {
    throw new Error('Invalid base64 payload')
  }
  const decoded = Buffer.from(normalized, 'base64')
  if (decoded.length < 1) {
    throw new Error('Empty attachment payload')
  }
  return decoded
}

function normalizeOpenClawAttachments(value: unknown): Array<{
  type: 'image'
  mimeType: string
  fileName?: string
  content: string
}> {
  if (!Array.isArray(value)) return []
  const normalized: Array<{
    type: 'image'
    mimeType: string
    fileName?: string
    content: string
  }> = []

  for (const row of value) {
    const record = asRecord(row)
    if (!record) continue
    const type = normalizeText(record.type)
    const mimeType = normalizeText(record.mimeType)
    const content = normalizeText(record.content)
    const fileName = normalizeText(record.fileName)
    if (type !== 'image' || !mimeType || !content) continue
    normalized.push({
      type: 'image',
      mimeType,
      content,
      fileName: fileName || undefined,
    })
  }

  return normalized
}

function buildOpenClawSessionKey(currentSessionKey = ''): string {
  const normalized = currentSessionKey.trim()
  const now = Date.now().toString(36)
  const rand = Math.random().toString(36).slice(2, 8)
  if (normalized.startsWith('agent:')) {
    const parts = normalized.split(':')
    const agent = normalizeText(parts[1]) || 'main'
    return `agent:${agent}:mobile-${now}-${rand}`
  }
  return `agent:main:mobile-${now}-${rand}`
}

function parseOpenClawSessionRows(value: unknown): Array<Record<string, unknown>> {
  const record = asRecord(value)
  if (!record) return []
  const sessions = record.sessions
  if (!Array.isArray(sessions)) return []
  return sessions.map((row) => asRecord(row)).filter((row): row is Record<string, unknown> => row !== null)
}

function findOpenClawSessionByKey(rows: Array<Record<string, unknown>>, key: string): Record<string, unknown> | null {
  const target = key.trim()
  if (!target) return null
  for (const row of rows) {
    const rowKey = normalizeText(row.key)
    if (rowKey === target) return row
  }
  return null
}

function collectOpenClawSessionLabels(rows: Array<Record<string, unknown>>): Set<string> {
  const labels = new Set<string>()
  for (const row of rows) {
    const displayName = normalizeText(row.displayName)
    if (displayName) labels.add(displayName)
    const label = normalizeText(row.label)
    if (label) labels.add(label)
  }
  return labels
}

function buildUniqueOpenClawSessionLabel(baseLabel: string, usedLabels: Set<string>): string {
  const base = baseLabel.trim() || '新会话'
  if (!usedLabels.has(base)) return base
  for (let index = 2; index <= 500; index += 1) {
    const candidate = `${base} ${index}`
    if (!usedLabels.has(candidate)) {
      return candidate
    }
  }
  return `${base} ${Date.now().toString(36)}`
}

function isOpenClawLabelConflictError(error: unknown): boolean {
  const message = getErrorMessage(error, '').toLowerCase()
  return message.includes('label already in use')
}

function pickNewestOpenClawSessionKey(
  rows: Array<Record<string, unknown>>,
  excludedSessionKey: string,
): string {
  const excluded = excludedSessionKey.trim()
  let bestKey = ''
  let bestUpdatedAt = -1
  for (const row of rows) {
    const key = normalizeText(row.key)
    if (!key || key === excluded) continue
    const updatedAt = typeof row.updatedAt === 'number' && Number.isFinite(row.updatedAt)
      ? row.updatedAt
      : 0
    if (updatedAt >= bestUpdatedAt) {
      bestKey = key
      bestUpdatedAt = updatedAt
    }
  }
  return bestKey
}

const OPENCLAW_SESSION_LIST_PARAMS = {
  limit: 400,
  includeDerivedTitles: true,
  includeLastMessage: true,
  includeGlobal: true,
  includeUnknown: true,
}

async function createIndependentOpenClawSession(
  currentSessionKey: string,
  label: string,
): Promise<string> {
  const baseLabel = label.trim() || '新会话'
  const usedLabels = new Set<string>()
  try {
    const existingRows = parseOpenClawSessionRows(
      await runOpenClawGatewayCall('sessions.list', OPENCLAW_SESSION_LIST_PARAMS),
    )
    const existingLabels = collectOpenClawSessionLabels(existingRows)
    for (const rowLabel of existingLabels) usedLabels.add(rowLabel)
  } catch {
    // Continue with optimistic create path when listing sessions is temporarily unavailable.
  }

  let lastError: unknown = null
  for (let createAttempt = 0; createAttempt < 5; createAttempt += 1) {
    const sessionKey = buildOpenClawSessionKey(currentSessionKey)
    const uniqueLabel = buildUniqueOpenClawSessionLabel(baseLabel, usedLabels)
    try {
      await runOpenClawGatewayCall('sessions.patch', {
        key: sessionKey,
        label: uniqueLabel,
      })
    } catch (error) {
      lastError = error
      if (isOpenClawLabelConflictError(error)) {
        usedLabels.add(uniqueLabel)
        await new Promise((resolve) => setTimeout(resolve, 90 + createAttempt * 60))
        continue
      }
      throw error
    }

    usedLabels.add(uniqueLabel)
    for (let persistAttempt = 0; persistAttempt < 8; persistAttempt += 1) {
      const sessionRows = parseOpenClawSessionRows(
        await runOpenClawGatewayCall('sessions.list', OPENCLAW_SESSION_LIST_PARAMS),
      )
      if (findOpenClawSessionByKey(sessionRows, sessionKey)) {
        return sessionKey
      }
      await new Promise((resolve) => setTimeout(resolve, 220 + persistAttempt * 120))
    }
    lastError = new Error('Failed to create OpenClaw session: session was not persisted')
    await new Promise((resolve) => setTimeout(resolve, 140 + createAttempt * 80))
  }

  if (lastError instanceof Error) {
    throw lastError
  }
  throw new Error('Failed to create OpenClaw session: session was not persisted')
}

async function resetCurrentOpenClawSession(currentSessionKey: string): Promise<string> {
  const current = currentSessionKey.trim()
  if (!current) {
    throw new Error('Failed to reset OpenClaw session: missing current session key')
  }

  await runOpenClawGatewayCall('chat.send', {
    sessionKey: current,
    message: '/new',
    deliver: true,
    idempotencyKey: `reset_${Date.now().toString(36)}`,
  })
  await new Promise((resolve) => setTimeout(resolve, 280))

  const sessionRows = parseOpenClawSessionRows(await runOpenClawGatewayCall('sessions.list', OPENCLAW_SESSION_LIST_PARAMS))
  if (findOpenClawSessionByKey(sessionRows, current)) {
    return current
  }
  const fallbackSessionKey = pickNewestOpenClawSessionKey(sessionRows, '')
  return fallbackSessionKey || current
}

function toLightweightSessionSummary(session: LightweightSession): Record<string, unknown> {
  return {
    key: session.key,
    displayName: session.title,
    label: session.title,
    updatedAt: session.updatedAt,
    lastMessagePreview: session.lastMessagePreview,
    modelProvider: session.modelProvider,
    model: session.model,
  }
}

async function listLightweightSessions(limit: number): Promise<Record<string, unknown>[]> {
  const state = await readLightweightState()
  if (state.sessions.length === 0) {
    const created = buildDefaultLightweightSession('agent:main:main')
    created.title = '新会话'
    created.updatedAt = Date.now()
    state.sessions.push(created)
    await writeLightweightState(state)
  }
  const sorted = [...state.sessions].sort((a, b) => b.updatedAt - a.updatedAt)
  return sorted.slice(0, limit).map(toLightweightSessionSummary)
}

async function createLightweightSession(label: string, currentSessionKey: string): Promise<string> {
  const state = await readLightweightState()
  const used = new Set(state.sessions.map((row) => row.title))
  const nextTitle = buildUniqueOpenClawSessionLabel(label.trim() || '新会话', used)
  const session = buildDefaultLightweightSession(buildOpenClawSessionKey(currentSessionKey))
  session.title = nextTitle
  session.updatedAt = Date.now()
  state.sessions.push(session)
  await writeLightweightState(state)
  return session.key
}

async function renameLightweightSession(sessionKey: string, label: string): Promise<void> {
  const key = sessionKey.trim()
  const nextLabel = label.trim()
  if (!key || !nextLabel) return
  const state = await readLightweightState()
  const target = state.sessions.find((row) => row.key === key)
  if (!target) {
    throw new Error('Session not found')
  }
  target.title = nextLabel
  target.updatedAt = Date.now()
  await writeLightweightState(state)
}

async function deleteLightweightSession(sessionKey: string): Promise<void> {
  const key = sessionKey.trim()
  if (!key) {
    throw new Error('Missing session key')
  }
  const state = await readLightweightState()
  const before = state.sessions.length
  state.sessions = state.sessions.filter((row) => row.key !== key)
  if (state.sessions.length === before) {
    throw new Error('Session not found')
  }
  if (state.sessions.length === 0) {
    const created = buildDefaultLightweightSession('agent:main:main')
    created.title = '新会话'
    created.updatedAt = Date.now()
    state.sessions.push(created)
  }
  await writeLightweightState(state)
}

async function resetLightweightSession(currentSessionKey: string): Promise<string> {
  const key = currentSessionKey.trim()
  if (!key) {
    throw new Error('Missing current session key')
  }
  const state = await readLightweightState()
  const target = state.sessions.find((row) => row.key === key)
  if (!target) {
    throw new Error('Session not found')
  }
  target.messages = []
  target.updatedAt = Date.now()
  target.lastMessagePreview = ''
  await writeLightweightState(state)
  return target.key
}

async function readLightweightHistory(sessionKey: string, limit: number): Promise<{
  sessionKey: string
  messages: LightweightHistoryMessage[]
  thinkingLevel: string
}> {
  const key = sessionKey.trim()
  if (!key) {
    throw new Error('Missing session key')
  }
  const state = await readLightweightState()
  const target = state.sessions.find((row) => row.key === key)
  if (!target) {
    const created = buildDefaultLightweightSession(key)
    state.sessions.push(created)
    await writeLightweightState(state)
    return {
      sessionKey: created.key,
      messages: [],
      thinkingLevel: 'medium',
    }
  }
  const messages = target.messages.slice(-Math.max(1, limit))
  return {
    sessionKey: target.key,
    messages,
    thinkingLevel: 'medium',
  }
}

function normalizeImageAttachmentsForLightweight(value: unknown): LightweightContentItem[] {
  if (!Array.isArray(value)) return []
  const items: LightweightContentItem[] = []
  for (const row of value) {
    const item = asRecord(row)
    if (!item) continue
    const type = normalizeText(item.type)
    if (type !== 'image') continue
    const mimeType = normalizeText(item.mimeType) || 'image/png'
    const content = normalizeText(item.content)
    if (!content) continue
    items.push({
      type: 'image',
      text: `[image:${mimeType}] data:image omitted`,
      arguments: {
        mimeType,
        sizeBytes: Math.floor((content.length * 3) / 4),
        fileName: normalizeText(item.fileName),
      },
    })
  }
  return items
}

async function runLightweightMessageInBackground(runId: string, sessionKey: string): Promise<void> {
  let state: LightweightState | null = null
  let session: LightweightSession | null = null
  try {
    state = await readLightweightState()
    session = await ensureLightweightSession(state, sessionKey)
    await runLightweightTurn(session, {
      isAborted: () => isLightweightRunAbortRequested(runId),
    })
    if (isLightweightRunAbortRequested(runId)) {
      throw new Error('lightweight-run-aborted')
    }
    session.updatedAt = Date.now()
    session.lastMessagePreview = buildSessionPreview(session.messages)
    await writeLightweightState(state)
    updateLightweightRun(runId, 'completed', true, '')
  } catch (error) {
    const message = getErrorMessage(error, '轻量调度执行失败')
    const aborted = message === 'lightweight-run-aborted' || isLightweightRunAbortRequested(runId)
    const renderedError = aborted ? '任务已中止。' : `任务执行失败：${message}`
    if (state && session) {
      session.messages.push({
        role: 'assistant',
        timestamp: Date.now(),
        content: toTextContent(renderedError),
      })
      session.updatedAt = Date.now()
      session.lastMessagePreview = buildSessionPreview(session.messages)
      await writeLightweightState(state)
    } else {
      // Best effort fallback: avoid leaving run state at running forever when bootstrap fails before session load.
      try {
        const fallbackState = await readLightweightState()
        const fallbackSession = await ensureLightweightSession(fallbackState, sessionKey)
        fallbackSession.messages.push({
          role: 'assistant',
          timestamp: Date.now(),
          content: toTextContent(renderedError),
        })
        fallbackSession.updatedAt = Date.now()
        fallbackSession.lastMessagePreview = buildSessionPreview(fallbackSession.messages)
        await writeLightweightState(fallbackState)
      } catch {
        // Ignore secondary persistence failures.
      }
    }
    updateLightweightRun(runId, aborted ? 'aborted' : 'failed', true, renderedError)
  }
}

async function sendLightweightMessage(payload: Record<string, unknown>): Promise<{ runId: string }> {
  const sessionKey = normalizeText(payload.sessionKey)
  const message = normalizeText(payload.message)
  const attachments = normalizeImageAttachmentsForLightweight(payload.attachments)
  if (!sessionKey || (!message && attachments.length === 0)) {
    throw new Error('Missing sessionKey and message/attachments')
  }

  const activeRunId = findLatestRunningLightweightRun(sessionKey)
  if (activeRunId) {
    throw new Error('已有任务正在执行，请等待完成或先终止当前任务')
  }

  const state = await readLightweightState()
  const session = await ensureLightweightSession(state, sessionKey)

  const userContent: LightweightContentItem[] = []
  if (message) {
    userContent.push(...toTextContent(message))
  }
  userContent.push(...attachments)
  session.messages.push({
    role: 'user',
    timestamp: Date.now(),
    content: userContent,
  })

  session.updatedAt = Date.now()
  session.lastMessagePreview = buildSessionPreview(session.messages)
  await writeLightweightState(state)
  const runId = `lite_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`
  rememberLightweightRun(runId, session.key)
  void runLightweightMessageInBackground(runId, session.key)

  return {
    runId,
  }
}

function buildCapabilitySummary(statusRecord: Record<string, unknown> | null): string {
  if (!statusRecord) return ''
  const installed = statusRecord.installed === true ? '1' : '0'
  const running = statusRecord.running === true ? '1' : '0'
  const granted = statusRecord.granted === true ? '1' : '0'
  const enabled = statusRecord.enabled === true ? '1' : '0'
  const executor = normalizeText(statusRecord.executor) || 'system-shell'
  const errorCode = normalizeText(statusRecord.last_error_code) || 'none'
  const checkedAt = normalizeText(statusRecord.checked_at) || new Date().toISOString()
  return `Current capability snapshot: installed=${installed} running=${running} granted=${granted} enabled=${enabled} executor=${executor} last_error_code=${errorCode} checked_at=${checkedAt}`
}

function buildRuntimeSummary(
  runtimeRecord: Record<string, unknown> | null,
  healthRecord: Record<string, unknown> | null,
): string {
  const runtimeVersion = normalizeText(runtimeRecord?.version) || 'missing'
  const runtimeInstalled = runtimeRecord ? '1' : '0'
  const runtimeCheckedAt = normalizeText(healthRecord?.checked_at)
  const runtimeHealthOk = healthRecord?.ok === true ? '1' : '0'
  const prefixDir = normalizeText(process.env.PREFIX)
  const runtimeBinDir = homeDir ? join(homeDir, '.openclaw-android', 'linux-runtime', 'bin') : ''
  const ubuntuBin =
    normalizeText(process.env.ANYCLAW_UBUNTU_BIN) ||
    (runtimeBinDir ? join(runtimeBinDir, 'ubuntu-shell.sh') : '')
  const pathValue = normalizeText(process.env.PATH)

  const lines = [
    `Current runtime snapshot: offline_linux_installed=${runtimeInstalled} version=${runtimeVersion} runtime_health_ok=${runtimeHealthOk}${runtimeCheckedAt ? ` checked_at=${runtimeCheckedAt}` : ''}`,
    homeDir ? `Current app home: ${homeDir}` : '',
    prefixDir ? `Current app prefix: ${prefixDir}` : '',
    runtimeBinDir ? `Current runtime bin: ${runtimeBinDir}` : '',
    ubuntuBin ? `Current Ubuntu bridge: ${ubuntuBin}` : '',
    pathValue ? `Current PATH: ${pathValue}` : '',
    'Execution chains available in this app: local app shell, Ubuntu runtime shell via ubuntu-shell or ANYCLAW_UBUNTU_BIN, OpenMinis Alpine via alpine-shell, and system-level shell via system-shell.',
    'The real visible OpenMinis browser is available through minis-browser and can be taken over by the user. Run minis-browser --help before first use; it documents every action and parameter. Start with list_tabs, use navigate, then wait_for_dom_stable before reading or interacting. click and type support CSS, XPath, or text selectors through --selector-type and automatically retry transient lookup failures. Use screenshot --output-path <absolute-path.png> --json, then inspect the returned imageFilePath with the image-view tool; do not guess screenshot paths. Browser history actions are back, forward, and reload.',
    'If the runtime snapshot above is installed and healthy, do not conclude Ubuntu is missing before verifying it with ubuntu-status, echo $ANYCLAW_UBUNTU_BIN, and ls "$HOME/.openclaw-android/linux-runtime/bin" in the local app shell.',
  ].filter((line) => line.length > 0)

  return lines.join('\n')
}

function shouldInjectDeveloperInstructions(method: string): boolean {
  return method === 'thread/start' || method === 'thread/resume'
}

function mergeDeveloperInstructions(existing: unknown, injected: string): string {
  const existingText = normalizeText(existing)
  const injectText = normalizeText(injected)
  if (!injectText) return existingText
  if (!existingText) return injectText
  if (existingText.includes(injectText)) return existingText
  return `${existingText}\n\n${injectText}`
}

async function buildInjectedDeveloperInstructions(routeParams?: Record<string, unknown>): Promise<string> {
  const promptRecord = await readJsonFile(promptInjectionPath)
  const statusRecord = await readJsonFile(shizukuStatusPath)
  const runtimeRecord = await readJsonFile(offlineLinuxRuntimePath)
  const healthRecord = await readJsonFile(runtimeHealthPath)

  const chunks: string[] = []
  const promptInstructions = normalizeText(promptRecord?.developer_instructions)
  if (promptInstructions) {
    chunks.push(promptInstructions)
  }

  const selectedName = normalizeText(promptRecord?.active_profile_name)
  if (selectedName) {
    chunks.push(`Selected prompt profile: ${selectedName}`)
  }

  const capabilitySummary = buildCapabilitySummary(statusRecord)
  if (capabilitySummary) {
    chunks.push(capabilitySummary)
  }

  const runtimeSummary = buildRuntimeSummary(runtimeRecord, healthRecord)
  if (runtimeSummary) {
    chunks.push(runtimeSummary)
  }

  const routeProviderId = normalizeText(routeParams?.modelProvider)
  const routeModel = normalizeText(routeParams?.model)
  if (routeProviderId || routeModel) {
    let providerName = routeProviderId || 'openai'
    let upstreamProtocol = routeProviderId === 'openai' ? 'responses' : ''
    try {
      const catalog = await readJsonFile(codexModelProvidersPath)
      const configs = Array.isArray(catalog?.configs) ? catalog.configs : []
      const selected = configs
        .map(asRecord)
        .find((row) => normalizeText(row?.providerId) === routeProviderId)
      providerName = normalizeText(selected?.displayName) || providerName
      upstreamProtocol = normalizeText(selected?.upstreamProtocol) || upstreamProtocol
    } catch {
      // Keep the route instruction useful even if the optional catalog is unavailable.
    }
    chunks.push(
      `Host model route for this thread: provider=${providerName} provider_id=${routeProviderId || 'openai'} model=${routeModel || 'default'} upstream_protocol=${upstreamProtocol || 'responses'}. If the user asks which model is active, report these host-provided route values instead of inferring from model self-identification.`,
    )
  }

  return chunks.join('\n\n').trim()
}

function toClaudeTextContent(text: string): ClaudeContentItem[] {
  const value = text.trim()
  if (!value) return []
  return [{ type: 'text', text: value }]
}

function buildClaudeSessionKey(currentSessionKey = ''): string {
  const normalized = currentSessionKey.trim()
  const now = Date.now().toString(36)
  const rand = Math.random().toString(36).slice(2, 8)
  if (normalized.startsWith('claude:')) {
    return `claude:${now}-${rand}`
  }
  return `claude:${now}-${rand}`
}

function buildClaudePreview(messages: ClaudeHistoryMessage[]): string {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const row = messages[index]
    const text = row.content
      .map((item) => normalizeText(item.text))
      .filter((value) => value.length > 0)
      .join('\n')
      .trim()
    if (text) {
      return truncateText(text, 140)
    }
  }
  return ''
}

function decodeXmlEntities(value: string): string {
  return value
    .replace(/&quot;/gu, '"')
    .replace(/&apos;/gu, '\'')
    .replace(/&lt;/gu, '<')
    .replace(/&gt;/gu, '>')
    .replace(/&amp;/gu, '&')
}

async function loadClaudeModelConfigFromSharedPrefs(): Promise<ClaudeModelConfig | null> {
  if (!homeDir) return null
  const appRoot = dirname(dirname(homeDir))
  const prefsPath = join(appRoot, 'shared_prefs', 'agent_model_configs.xml')
  let raw = ''
  try {
    raw = await readFile(prefsPath, 'utf8')
  } catch {
    return null
  }
  const match = raw.match(/<string name="configs_json">([\s\S]*?)<\/string>/u)
  if (!match) return null
  const encoded = match[1] ?? ''
  const decoded = decodeXmlEntities(encoded).trim()
  if (!decoded) return null

  let list: unknown = null
  try {
    list = JSON.parse(decoded)
  } catch {
    return null
  }
  if (!Array.isArray(list)) return null

  const rows = list
    .map((row) => asRecord(row))
    .filter((row): row is Record<string, unknown> => row !== null)
    .filter((row) => normalizeText(row.agentId) === 'claude-code')
    .filter((row) => normalizeText(row.modelId) && normalizeText(row.baseUrl) && normalizeText(row.apiKey))

  if (rows.length === 0) return null
  const target = rows.find((row) => row.isDefault === true) ?? rows[0]
  return {
    providerName: normalizeText(target.providerName) || 'claude',
    modelId: normalizeText(target.modelId),
    baseUrl: normalizeBaseUrl(normalizeText(target.baseUrl)),
    apiKey: normalizeText(target.apiKey),
  }
}

async function readClaudeState(): Promise<ClaudeState> {
  try {
    const raw = await readFile(CLAUDE_STATE_PATH, 'utf8')
    const parsed = JSON.parse(raw) as unknown
    const root = asRecord(parsed)
    const sessionsRaw = Array.isArray(root?.sessions) ? root.sessions : []
    const sessions: ClaudeSession[] = []
    for (const row of sessionsRaw) {
      const item = asRecord(row)
      if (!item) continue
      const key = normalizeText(item.key)
      if (!key) continue
      const title = normalizeText(item.title) || key
      const updatedAt = typeof item.updatedAt === 'number' && Number.isFinite(item.updatedAt)
        ? Math.floor(item.updatedAt)
        : Date.now()
      const lastMessagePreview = normalizeText(item.lastMessagePreview)
      const modelProvider = normalizeText(item.modelProvider)
      const model = normalizeText(item.model)
      const messagesRaw = Array.isArray(item.messages) ? item.messages : []
      const messages: ClaudeHistoryMessage[] = []
      for (const msgRow of messagesRaw) {
        const msg = asRecord(msgRow)
        if (!msg) continue
        const role = normalizeText(msg.role) || 'assistant'
        const timestamp = typeof msg.timestamp === 'number' && Number.isFinite(msg.timestamp)
          ? Math.floor(msg.timestamp)
          : Date.now()
        const contentRaw = Array.isArray(msg.content) ? msg.content : []
        const content: ClaudeContentItem[] = contentRaw
          .map((contentRow) => asRecord(contentRow))
          .filter((contentRow): contentRow is Record<string, unknown> => contentRow !== null)
          .map((contentRow) => ({
            type: normalizeText(contentRow.type) || 'text',
            text: normalizeText(contentRow.text),
          }))
          .filter((contentRow) => contentRow.text || contentRow.type !== 'text')
        messages.push({
          role,
          timestamp,
          content,
          toolName: normalizeText(msg.toolName) || undefined,
          isError: msg.isError === true,
        })
      }
      sessions.push({
        key,
        title,
        updatedAt,
        lastMessagePreview,
        modelProvider,
        model,
        messages,
      })
    }
    return { sessions }
  } catch {
    return { sessions: [] }
  }
}

async function writeClaudeState(state: ClaudeState): Promise<void> {
  await mkdir(dirname(CLAUDE_STATE_PATH), { recursive: true })
  await writeFile(
    CLAUDE_STATE_PATH,
    JSON.stringify({ sessions: state.sessions }, null, 2),
    { mode: 0o600 },
  )
}

async function ensureClaudeSession(state: ClaudeState, sessionKey: string): Promise<ClaudeSession> {
  const key = sessionKey.trim()
  if (!key) {
    throw new Error('Missing session key')
  }
  let target = state.sessions.find((row) => row.key === key)
  if (target) return target
  target = {
    key,
    title: 'Claude 会话',
    updatedAt: Date.now(),
    lastMessagePreview: '',
    modelProvider: '',
    model: '',
    messages: [],
  }
  state.sessions.push(target)
  return target
}

function toClaudeSessionSummary(session: ClaudeSession): Record<string, unknown> {
  return {
    key: session.key,
    displayName: session.title,
    label: session.title,
    updatedAt: session.updatedAt,
    lastMessagePreview: session.lastMessagePreview,
    modelProvider: session.modelProvider,
    model: session.model,
  }
}

async function listClaudeSessions(limit: number): Promise<Record<string, unknown>[]> {
  const state = await readClaudeState()
  if (state.sessions.length === 0) {
    const created: ClaudeSession = {
      key: buildClaudeSessionKey(''),
      title: 'Claude 会话',
      updatedAt: Date.now(),
      lastMessagePreview: '',
      modelProvider: '',
      model: '',
      messages: [],
    }
    state.sessions.push(created)
    await writeClaudeState(state)
  }
  return [...state.sessions]
    .sort((a, b) => b.updatedAt - a.updatedAt)
    .slice(0, Math.max(1, limit))
    .map(toClaudeSessionSummary)
}

async function createClaudeSession(label: string, currentSessionKey: string): Promise<string> {
  const state = await readClaudeState()
  const used = new Set(state.sessions.map((row) => row.title))
  const title = buildUniqueOpenClawSessionLabel(label.trim() || 'Claude 会话', used)
  const key = buildClaudeSessionKey(currentSessionKey)
  state.sessions.push({
    key,
    title,
    updatedAt: Date.now(),
    lastMessagePreview: '',
    modelProvider: '',
    model: '',
    messages: [],
  })
  await writeClaudeState(state)
  return key
}

async function resetClaudeSession(currentSessionKey: string): Promise<string> {
  const state = await readClaudeState()
  const target = state.sessions.find((row) => row.key === currentSessionKey.trim())
  if (!target) {
    throw new Error('Session not found')
  }
  target.messages = []
  target.updatedAt = Date.now()
  target.lastMessagePreview = ''
  await writeClaudeState(state)
  return target.key
}

async function renameClaudeSession(sessionKey: string, title: string): Promise<void> {
  const state = await readClaudeState()
  const target = state.sessions.find((row) => row.key === sessionKey.trim())
  if (!target) {
    throw new Error('Session not found')
  }
  target.title = title.trim() || target.title
  target.updatedAt = Date.now()
  await writeClaudeState(state)
}

function claudeMessageText(message: ClaudeHistoryMessage): string {
  return message.content
    .filter((item) => item.type === 'text' && typeof item.text === 'string')
    .map((item) => item.text?.trim() ?? '')
    .filter(Boolean)
    .join('\n')
    .trim()
}

function isInternalCollaborationCoordinatorPrompt(value: string): boolean {
  const text = value.trim()
  return text.startsWith('[口袋大龙虾三智能体协作：总调度') ||
    text.startsWith('[三智能体协作任务：总调度最终审核]')
}

function visibleCollaborationUserPrompt(value: string): string {
  const text = value.trim()
  const backgroundMarker = '用户原始消息仅作为背景，不代表要求您重复执行全部任务：'
  if (text.includes(backgroundMarker)) return text.slice(text.lastIndexOf(backgroundMarker) + backgroundMarker.length).trim()
  const requestMarker = '用户原始请求：'
  if (text.includes(requestMarker)) return text.slice(text.lastIndexOf(requestMarker) + requestMarker.length).trim()
  return ''
}

function projectClaudeVisibleHistory(messages: ClaudeHistoryMessage[]): ClaudeHistoryMessage[] {
  const visible: ClaudeHistoryMessage[] = []
  let hideCoordinatorResponse = false
  for (const message of messages) {
    if (message.role === 'user') {
      const raw = claudeMessageText(message)
      if (isInternalCollaborationCoordinatorPrompt(raw)) {
        hideCoordinatorResponse = true
        continue
      }
      const isCollaboration = raw.startsWith('[口袋大龙虾三智能体协作') || raw.startsWith('[三智能体协作任务')
      if (isCollaboration) {
        const publicPrompt = visibleCollaborationUserPrompt(raw)
        if (!publicPrompt) continue
        visible.push({ ...message, content: [{ type: 'text', text: publicPrompt }] })
        continue
      }
      visible.push(message)
      continue
    }
    if (hideCoordinatorResponse) {
      if (message.role === 'assistant') hideCoordinatorResponse = false
      continue
    }
    visible.push(message)
  }
  return visible
}

async function readClaudeHistory(sessionKey: string, limit: number): Promise<{
  sessionKey: string
  messages: ClaudeHistoryMessage[]
  thinkingLevel: string
}> {
  const state = await readClaudeState()
  const target = await ensureClaudeSession(state, sessionKey)
  const normalizedLimit = clampInt(limit, 10, 400)
  const messages = projectClaudeVisibleHistory(target.messages).slice(-normalizedLimit)
  await writeClaudeState(state)
  return {
    sessionKey: target.key,
    messages,
    thinkingLevel: 'medium',
  }
}

function stripAnsiCodes(value: string): string {
  return value.replace(/\u001B\[[0-9;]*[A-Za-z]/gu, '')
}

function isClaudeProcessLine(line: string): boolean {
  const text = line.trim()
  if (!text) return false
  if (/^INFO\s+\d{4}-\d{2}-\d{2}/u.test(text)) return true
  if (/^(DEBUG|TRACE|WARN)\s+\d{4}-\d{2}-\d{2}/u.test(text)) return true
  if (text.includes('service=') && text.includes('status=')) return true
  if (text.includes('tool.registry')) return true
  if (text.includes('sqlite-migration:')) return true
  if (text.includes('Database migration complete.')) return true
  if (text.startsWith('Performing one time database migration')) return true
  return false
}

function trimClaudeProcessLines(lines: string[]): string[] {
  if (lines.length <= CLAUDE_PROCESS_LINES_MAX) return lines
  return lines.slice(lines.length - CLAUDE_PROCESS_LINES_MAX)
}

function extractClaudeAssistantText(raw: string): string {
  const cleaned = stripAnsiCodes(raw)
    .split(/\r?\n/u)
    .map((line) => line.trimEnd())
    .filter((line) =>
      !line.startsWith('Claude Code Warning: no stdin data received') &&
      !line.startsWith('If piping from a slow command, redirect stdin explicitly:'),
    )
  const nonProcess = cleaned.filter((line) => !isClaudeProcessLine(line))
  const text = nonProcess.join('\n').trim()
  if (text) return truncateText(text, CLAUDE_OUTPUT_CHARS_MAX)
  return truncateText(cleaned.join('\n').trim(), CLAUDE_OUTPUT_CHARS_MAX)
}

function collectClaudeProcessLines(raw: string): string[] {
  const lines = stripAnsiCodes(raw)
    .split(/\r?\n/u)
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .filter((line) => isClaudeProcessLine(line))
  return trimClaudeProcessLines(lines)
}

function toPersistedClaudeRun(run: ClaudeRunContext): ClaudePersistedRun {
  return {
    runId: run.runId,
    sessionKey: run.sessionKey,
    status: run.status,
    startedAtMs: run.startedAtMs,
    updatedAtMs: run.updatedAtMs,
    lastOutputAtMs: run.lastOutputAtMs,
    completed: run.completed,
    processLines: trimClaudeProcessLines(run.processLines),
    assistantText: run.assistantText,
    errorText: run.errorText,
    exitCode: run.exitCode,
  }
}

function normalizeClaudePersistedRun(value: unknown): ClaudePersistedRun | null {
  const row = asRecord(value)
  if (!row) return null
  const runId = normalizeText(row.runId)
  const sessionKey = normalizeText(row.sessionKey)
  if (!runId || !sessionKey) return null
  const startedAtMs = typeof row.startedAtMs === 'number' && Number.isFinite(row.startedAtMs)
    ? Math.floor(row.startedAtMs)
    : Date.now()
  const updatedAtMs = typeof row.updatedAtMs === 'number' && Number.isFinite(row.updatedAtMs)
    ? Math.floor(row.updatedAtMs)
    : startedAtMs
  const lastOutputAtMs = typeof row.lastOutputAtMs === 'number' && Number.isFinite(row.lastOutputAtMs)
    ? Math.floor(row.lastOutputAtMs)
    : updatedAtMs
  const processLinesRaw = Array.isArray(row.processLines) ? row.processLines : []
  const processLines = trimClaudeProcessLines(
    processLinesRaw
      .map((item) => normalizeText(item))
      .filter((item) => item.length > 0),
  )
  return {
    runId,
    sessionKey,
    status: normalizeText(row.status) || 'running',
    startedAtMs,
    updatedAtMs,
    lastOutputAtMs,
    completed: row.completed === true,
    processLines,
    assistantText: normalizeText(row.assistantText),
    errorText: normalizeText(row.errorText),
    exitCode: typeof row.exitCode === 'number' && Number.isFinite(row.exitCode) ? Math.floor(row.exitCode) : null,
  }
}

async function readClaudeRunsState(): Promise<ClaudePersistedRun[]> {
  try {
    const raw = await readFile(CLAUDE_RUNS_STATE_PATH, 'utf8')
    const parsed = JSON.parse(raw) as unknown
    const record = asRecord(parsed)
    const rows = Array.isArray(record?.runs) ? record.runs : []
    const runs: ClaudePersistedRun[] = []
    for (const row of rows) {
      const normalized = normalizeClaudePersistedRun(row)
      if (normalized) runs.push(normalized)
    }
    return runs
  } catch {
    return []
  }
}

async function writeClaudeRunsState(runs: ClaudePersistedRun[]): Promise<void> {
  await mkdir(dirname(CLAUDE_RUNS_STATE_PATH), { recursive: true })
  await writeFile(
    CLAUDE_RUNS_STATE_PATH,
    JSON.stringify({ runs }, null, 2),
    { mode: 0o600 },
  )
}

async function persistClaudeRunsNow(): Promise<void> {
  const snapshot = [...claudeRuns.values()].map(toPersistedClaudeRun)
  await writeClaudeRunsState(snapshot)
}

function scheduleClaudeRunsPersist(delayMs = 280): void {
  if (claudeRunsPersistTimer !== null) return
  claudeRunsPersistTimer = setTimeout(() => {
    claudeRunsPersistTimer = null
    void persistClaudeRunsNow()
  }, Math.max(80, delayMs))
}

function reapStaleClaudeRuns(): void {
  const now = Date.now()
  let removed = false
  for (const [runId, run] of claudeRuns.entries()) {
    if (!run.completed) continue
    if (now - run.updatedAtMs > CLAUDE_RUN_CONTEXT_TTL_MS) {
      claudeRuns.delete(runId)
      removed = true
    }
  }
  if (removed) {
    scheduleClaudeRunsPersist()
  }
}

function buildClaudePrompt(message: string, attachmentPaths: string[]): string {
  const trimmed = message.trim()
  if (attachmentPaths.length === 0) return trimmed
  const rows = attachmentPaths.map((path) => `PATH::${path}`)
  const filesSection = [
    '附件路径如下，请逐一读取并基于这些路径完成任务：',
    'PATH_BEGIN',
    ...rows,
    'PATH_END',
  ].join('\n')
  if (!trimmed) {
    return `${filesSection}\n\n请先确认可访问以上路径。`
  }
  return `${trimmed}\n\n${filesSection}`
}

function ensurePrefixPath(env: NodeJS.ProcessEnv): NodeJS.ProcessEnv {
  const next = { ...env }
  if (prefixBin) {
    const currentPath = typeof next.PATH === 'string' ? next.PATH : ''
    if (!currentPath.split(':').includes(prefixBin)) {
      next.PATH = currentPath.length > 0 ? `${prefixBin}:${currentPath}` : prefixBin
    }
  }
  return next
}

async function finalizeClaudeRun(runId: string): Promise<void> {
  const run = claudeRuns.get(runId)
  if (!run) return
  const state = await readClaudeState()
  const session = await ensureClaudeSession(state, run.sessionKey)
  const processLines = trimClaudeProcessLines(run.processLines)
  if (processLines.length > 0) {
    session.messages.push({
      role: 'toolResult',
      timestamp: Date.now(),
      toolName: 'claude.process',
      isError: false,
      content: toClaudeTextContent(processLines.join('\n')),
    })
  }
  if (run.assistantText.trim()) {
    session.messages.push({
      role: 'assistant',
      timestamp: Date.now(),
      content: toClaudeTextContent(run.assistantText),
    })
  } else if (run.errorText.trim()) {
    session.messages.push({
      role: 'assistant',
      timestamp: Date.now(),
      content: toClaudeTextContent(`Claude 执行失败：${run.errorText}`),
    })
  }
  session.updatedAt = Date.now()
  session.lastMessagePreview = buildClaudePreview(session.messages)
  await writeClaudeState(state)
  scheduleClaudeRunsPersist()
}

async function sendClaudeMessage(payload: Record<string, unknown>): Promise<{ runId: string }> {
  const sessionKey = normalizeText(payload.sessionKey)
  const message = normalizeText(payload.message)
  const attachmentPaths = Array.isArray(payload.attachmentPaths)
    ? payload.attachmentPaths
      .map((row) => normalizeText(row))
      .filter((row) => row.length > 0)
    : []
  if (!sessionKey || (!message && attachmentPaths.length === 0)) {
    throw new Error('Missing sessionKey and message/attachments')
  }

  const model = await loadClaudeModelConfigFromSharedPrefs()
  if (!model || !model.apiKey || !model.baseUrl || !model.modelId) {
    throw new Error('Claude model is not configured, please save Claude model in 模型管理 first')
  }

  const allowSharedStorage = payload.allowSharedStorage === true
  const dangerousMode = payload.dangerousMode === true

  const state = await readClaudeState()
  const session = await ensureClaudeSession(state, sessionKey)
  const prompt = buildClaudePrompt(message, attachmentPaths)
  session.messages.push({
    role: 'user',
    timestamp: Date.now(),
    content: toClaudeTextContent(prompt),
  })
  session.model = model.modelId
  session.modelProvider = model.providerName
  session.updatedAt = Date.now()
  session.lastMessagePreview = buildClaudePreview(session.messages)
  await writeClaudeState(state)

  const dirs = [homeDir].filter((row) => row.length > 0)
  if (allowSharedStorage) {
    dirs.push('/sdcard', '/storage/emulated/0')
  }
  const addDirArg = dirs.map((dir) => `--add-dir ${shellQuote(dir)}`).join(' ')
  const modelArg = model.modelId ? `--model ${shellQuote(model.modelId)} ` : ''
  const baseEnv = model.baseUrl ? `ANTHROPIC_BASE_URL=${shellQuote(model.baseUrl)} ` : ''
  const keyEnv = `ANTHROPIC_API_KEY=${shellQuote(model.apiKey)} `
  const dangerArg = dangerousMode ? '--dangerously-skip-permissions ' : ''
  const cmd = `${baseEnv}${keyEnv}claude -p ${dangerArg}${addDirArg} ${modelArg}${shellQuote(prompt)} < /dev/null 2>&1`

  const runId = `claude_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`
  const run: ClaudeRunContext = {
    runId,
    sessionKey,
    status: 'running',
    startedAtMs: Date.now(),
    updatedAtMs: Date.now(),
    lastOutputAtMs: Date.now(),
    completed: false,
    processLines: [],
    rawOutput: '',
    assistantText: '',
    errorText: '',
    exitCode: null,
    process: null,
  }
  claudeRuns.set(runId, run)
  reapStaleClaudeRuns()
  scheduleClaudeRunsPersist(120)

  const env = ensurePrefixPath({
    ...process.env,
    NO_COLOR: '1',
    CI: '1',
  })

  const proc = spawn(shellPath, ['-lc', cmd], {
    cwd: homeDir || process.cwd(),
    env,
    stdio: ['ignore', 'pipe', 'pipe'],
    detached: true,
  })
  run.process = proc
  proc.stdout.setEncoding('utf8')
  proc.stderr.setEncoding('utf8')
  const appendOutput = (chunk: string) => {
    run.rawOutput = truncateText(run.rawOutput + String(chunk), CLAUDE_OUTPUT_CHARS_MAX)
    run.processLines = collectClaudeProcessLines(run.rawOutput)
    run.updatedAtMs = Date.now()
    run.lastOutputAtMs = run.updatedAtMs
    scheduleClaudeRunsPersist()
  }
  proc.stdout.on('data', appendOutput)
  proc.stderr.on('data', appendOutput)
  proc.on('error', async (error) => {
    if (run.completed) return
    run.errorText = getErrorMessage(error, 'claude process start failed')
    run.status = 'failed'
    run.completed = true
    run.exitCode = 127
    run.process = null
    run.assistantText = extractClaudeAssistantText(run.rawOutput)
    run.updatedAtMs = Date.now()
    run.lastOutputAtMs = run.updatedAtMs
    scheduleClaudeRunsPersist()
    await finalizeClaudeRun(runId)
  })
  proc.on('close', async (code) => {
    if (run.completed) return
    run.exitCode = typeof code === 'number' ? code : null
    run.assistantText = extractClaudeAssistantText(run.rawOutput)
    if ((run.exitCode ?? 1) === 0) {
      run.status = 'completed'
      run.errorText = ''
    } else if (run.status !== 'aborted') {
      run.status = 'failed'
      const fallbackError = normalizeText(run.rawOutput) || `exit code ${String(run.exitCode ?? -1)}`
      run.errorText = truncateText(fallbackError, 4000)
    }
    run.completed = true
    run.process = null
    run.updatedAtMs = Date.now()
    run.lastOutputAtMs = run.updatedAtMs
    scheduleClaudeRunsPersist()
    await finalizeClaudeRun(runId)
  })
  return { runId }
}

function buildClaudeWatchdogMessage(lastOutputAtMs: number, completed: boolean): string {
  if (completed) return ''
  const idleMs = Math.max(0, Date.now() - lastOutputAtMs)
  if (idleMs < CLAUDE_NO_OUTPUT_WARN_MS) return ''
  return `Claude long task still running, no new output for ${Math.floor(idleMs / 1000)}s`
}

function toClaudeStatusPayloadFromPersisted(run: ClaudePersistedRun): Record<string, unknown> {
  const processText = trimClaudeProcessLines(run.processLines).join('\n')
  const watchdog = buildClaudeWatchdogMessage(run.lastOutputAtMs, run.completed)
  const result = {
    processText: truncateText(processText, 12_000),
    assistantText: truncateText(run.assistantText, 12_000),
    exitCode: run.exitCode,
    watchdog: watchdog || null,
  }
  if (run.completed) {
    return {
      ok: run.status === 'completed',
      runId: run.runId,
      status: run.status || 'completed',
      completed: true,
      result,
      error: run.errorText || null,
    }
  }
  return {
    ok: true,
    runId: run.runId,
    status: run.status || 'running',
    completed: false,
    retryable: true,
    detached: true,
    result,
    error: run.errorText || null,
  }
}

function toClaudeStatusPayloadFromContext(run: ClaudeRunContext): Record<string, unknown> {
  const payload = toClaudeStatusPayloadFromPersisted(toPersistedClaudeRun(run))
  if (payload.completed !== true) {
    payload.detached = false
  }
  return payload
}

function extractClaudeMessageText(message: ClaudeHistoryMessage): string {
  const chunks: string[] = []
  for (const item of message.content) {
    if (item.type !== 'text') continue
    const text = normalizeText(item.text)
    if (text) chunks.push(text)
  }
  return chunks.join('\n\n').trim()
}

async function probeClaudeRunCompletionByHistory(run: ClaudePersistedRun): Promise<Record<string, unknown> | null> {
  try {
    const state = await readClaudeState()
    const session = state.sessions.find((row) => row.key === run.sessionKey)
    if (!session) return null
    for (let index = session.messages.length - 1; index >= 0; index -= 1) {
      const row = session.messages[index]
      if (row.role !== 'assistant') continue
      if (row.timestamp < run.startedAtMs) continue
      const text = extractClaudeMessageText(row)
      if (!text) continue
      const completed: ClaudePersistedRun = {
        ...run,
        status: 'completed',
        completed: true,
        assistantText: text,
        errorText: '',
        updatedAtMs: Date.now(),
        lastOutputAtMs: Date.now(),
      }
      const rows = await readClaudeRunsState()
      const nextRows = rows.map((item) => (item.runId === run.runId ? completed : item))
      await writeClaudeRunsState(nextRows)
      return {
        ok: true,
        runId: run.runId,
        status: 'completed',
        completed: true,
        source: 'history-probe',
        result: {
          processText: truncateText(trimClaudeProcessLines(run.processLines).join('\n'), 12_000),
          assistantText: truncateText(text, 12_000),
          exitCode: run.exitCode,
          watchdog: null,
        },
        error: null,
      }
    }
    return null
  } catch {
    return null
  }
}

async function getClaudeRunStatus(runId: string): Promise<Record<string, unknown>> {
  const run = claudeRuns.get(runId)
  if (run) {
    return toClaudeStatusPayloadFromContext(run)
  }

  const persistedRuns = await readClaudeRunsState()
  const persisted = persistedRuns.find((item) => item.runId === runId)
  if (persisted) {
    if (persisted.completed) {
      return toClaudeStatusPayloadFromPersisted(persisted)
    }
    const probed = await probeClaudeRunCompletionByHistory(persisted)
    if (probed) return probed
    return toClaudeStatusPayloadFromPersisted(persisted)
  }

  return {
    ok: false,
    runId,
    status: 'unknown',
    completed: false,
    retryable: true,
    error: 'Run state unavailable, please retry wait or refresh history',
  }
}

async function abortClaudeRun(runId: string): Promise<boolean> {
  const run = claudeRuns.get(runId)
  if (!run) return false
  if (run.completed) return true
  run.status = 'aborted'
  run.errorText = 'aborted by user'
  run.updatedAtMs = Date.now()
  try {
    const pid = run.process?.pid
    if (typeof pid === 'number' && pid > 0) process.kill(-pid, 'SIGTERM')
    else run.process?.kill('SIGTERM')
  } catch {
    try { run.process?.kill('SIGTERM') } catch { /* ignore */ }
  }
  await sleepMs(120)
  try {
    const pid = run.process?.pid
    if (typeof pid === 'number' && pid > 0) process.kill(-pid, 'SIGKILL')
    else run.process?.kill('SIGKILL')
  } catch {
    try { run.process?.kill('SIGKILL') } catch { /* ignore */ }
  }
  run.completed = true
  run.exitCode = 130
  run.process = null
  run.lastOutputAtMs = Date.now()
  scheduleClaudeRunsPersist()
  await finalizeClaudeRun(runId)
  return true
}

class AppServerProcess {
  private process: ChildProcessWithoutNullStreams | null = null
  private initialized = false
  private readBuffer = ''
  private nextId = 1
  private stopping = false
  private lastStartError: Error | null = null
  private readonly pending = new Map<number, {
    resolve: (value: unknown) => void
    reject: (reason?: unknown) => void
    timeout: ReturnType<typeof setTimeout>
  }>()
  private readonly notificationListeners = new Set<(value: SequencedNotification) => void>()
  private readonly notificationHistory: SequencedNotification[] = []
  private notificationSequence = 0
  private readonly pendingServerRequests = new Map<number, PendingServerRequest>()
  private readonly codexBin = prefixBin ? join(prefixBin, 'codex') : 'codex'

  isCommandAvailable(): boolean {
    if (!prefixBin) return true
    try {
      accessSync(this.codexBin, fsConstants.X_OK)
      return true
    } catch {
      return false
    }
  }

  getUnavailableReason(): string {
    if (!this.isCommandAvailable()) {
      return `Codex CLI not installed: missing executable ${this.codexBin}`
    }
    if (this.lastStartError && this.lastStartError.message.trim().length > 0) {
      return this.lastStartError.message
    }
    return ''
  }

  private start(): void {
    if (this.process) return

    if (!this.isCommandAvailable()) {
      const failure = new Error(`Codex CLI not installed: missing executable ${this.codexBin}`)
      this.lastStartError = failure
      throw failure
    }

    this.stopping = false
    reloadCodexProviderSecretEnvironment()
    void appendCodexDiagnostic('engine_initialized', {
      engine: 'codex app-server',
      transport: 'json-rpc',
      pid: process.pid,
    })
    const proc = spawn(this.codexBin, ['app-server'], {
      stdio: ['pipe', 'pipe', 'pipe'],
      env: { ...process.env },
    })
    this.process = proc
    this.lastStartError = null

    proc.stdout.setEncoding('utf8')
    proc.stdout.on('data', (chunk: string) => {
      if (this.process !== proc) return
      this.readBuffer += chunk

      let lineEnd = this.readBuffer.indexOf('\n')
      while (lineEnd !== -1) {
        const line = this.readBuffer.slice(0, lineEnd).trim()
        this.readBuffer = this.readBuffer.slice(lineEnd + 1)

        if (line.length > 0) {
          this.handleLine(line)
        }

        lineEnd = this.readBuffer.indexOf('\n')
      }
    })

    proc.stderr.setEncoding('utf8')
    proc.stderr.on('data', () => {
      // Keep stderr silent in dev middleware; JSON-RPC errors are forwarded via responses.
    })

    proc.on('error', (error) => {
      if (this.process !== proc) return
      const failure = new Error(`codex app-server start failed: ${getErrorMessage(error, 'unknown error')}`)
      this.lastStartError = failure
      for (const request of this.pending.values()) {
        clearTimeout(request.timeout)
        request.reject(failure)
      }
      this.pending.clear()
      this.pendingServerRequests.clear()
      this.process = null
      this.initialized = false
      this.readBuffer = ''
    })

    proc.on('exit', () => {
      if (this.process !== proc) return
      const failure = new Error(this.stopping ? 'codex app-server stopped' : 'codex app-server exited unexpectedly')
      this.lastStartError = this.stopping ? null : failure
      for (const request of this.pending.values()) {
        clearTimeout(request.timeout)
        request.reject(failure)
      }

      this.pending.clear()
      this.pendingServerRequests.clear()
      this.process = null
      this.initialized = false
      this.readBuffer = ''
    })
  }

  private sendLine(payload: Record<string, unknown>): void {
    if (!this.process) {
      throw new Error('codex app-server is not running')
    }

    this.process.stdin.write(`${JSON.stringify(payload)}\n`)
  }

  private handleLine(line: string): void {
    let message: JsonRpcResponse
    try {
      message = JSON.parse(line) as JsonRpcResponse
    } catch {
      return
    }

    if (typeof message.id === 'number' && this.pending.has(message.id)) {
      const pendingRequest = this.pending.get(message.id)
      this.pending.delete(message.id)

      if (!pendingRequest) return
      clearTimeout(pendingRequest.timeout)

      if (message.error) {
        pendingRequest.reject(new Error(message.error.message))
      } else {
        pendingRequest.resolve(message.result)
      }
      return
    }

    if (typeof message.method === 'string' && typeof message.id !== 'number') {
      this.emitNotification({
        method: message.method,
        params: message.params ?? null,
      })
      return
    }

    // Handle server-initiated JSON-RPC requests (approvals, dynamic tool calls, etc.).
    if (typeof message.id === 'number' && typeof message.method === 'string') {
      this.handleServerRequest(message.id, message.method, message.params ?? null)
    }
  }

  private emitNotification(notification: { method: string; params: unknown }): void {
    if (notification.method === 'turn/completed' || notification.method === 'turn/started') {
      const params = asRecord(notification.params)
      const turn = asRecord(params?.turn)
      void appendCodexDiagnostic('codex_notification', {
        method: notification.method,
        threadId: normalizeText(params?.threadId),
        turnId: normalizeText(params?.turnId) || normalizeText(turn?.id),
        status: normalizeText(turn?.status),
        error: normalizeText(asRecord(turn?.error)?.message).slice(0, 800),
      })
    }
    const sequenced: SequencedNotification = {
      sequence: ++this.notificationSequence,
      ...notification,
    }
    this.notificationHistory.push(sequenced)
    if (this.notificationHistory.length > CODEX_NOTIFICATION_HISTORY_LIMIT) {
      this.notificationHistory.splice(0, this.notificationHistory.length - CODEX_NOTIFICATION_HISTORY_LIMIT)
    }
    for (const listener of this.notificationListeners) {
      listener(sequenced)
    }
  }

  private sendServerRequestReply(requestId: number, reply: ServerRequestReply): void {
    if (reply.error) {
      this.sendLine({
        jsonrpc: '2.0',
        id: requestId,
        error: reply.error,
      })
      return
    }

    this.sendLine({
      jsonrpc: '2.0',
      id: requestId,
      result: reply.result ?? {},
    })
  }

  private resolvePendingServerRequest(requestId: number, reply: ServerRequestReply): void {
    const pendingRequest = this.pendingServerRequests.get(requestId)
    if (!pendingRequest) {
      throw new Error(`No pending server request found for id ${String(requestId)}`)
    }
    this.pendingServerRequests.delete(requestId)

    this.sendServerRequestReply(requestId, reply)
    const requestParams = asRecord(pendingRequest.params)
    const threadId =
      typeof requestParams?.threadId === 'string' && requestParams.threadId.length > 0
        ? requestParams.threadId
        : ''
    this.emitNotification({
      method: 'server/request/resolved',
      params: {
        id: requestId,
        method: pendingRequest.method,
        threadId,
        mode: 'manual',
        resolvedAtIso: new Date().toISOString(),
      },
    })
  }

  private handleServerRequest(requestId: number, method: string, params: unknown): void {
    const pendingRequest: PendingServerRequest = {
      id: requestId,
      method,
      params,
      receivedAtIso: new Date().toISOString(),
    }
    this.pendingServerRequests.set(requestId, pendingRequest)

    this.emitNotification({
      method: 'server/request',
      params: pendingRequest,
    })
  }

  private async call(method: string, params: unknown): Promise<unknown> {
    this.start()
    const id = this.nextId++

    return new Promise((resolve, reject) => {
      const timeoutMs = method === 'initialize' ? CODEX_INITIALIZE_TIMEOUT_MS : CODEX_RPC_TIMEOUT_MS
      const timeout = setTimeout(() => {
        if (!this.pending.delete(id)) return
        reject(new Error(`Codex RPC ${method} timed out after ${String(timeoutMs)}ms`))
      }, timeoutMs)
      this.pending.set(id, { resolve, reject, timeout })
      try {
        this.sendLine({
          jsonrpc: '2.0',
          id,
          method,
          params,
        } satisfies JsonRpcCall)
      } catch (error) {
        clearTimeout(timeout)
        this.pending.delete(id)
        reject(error)
      }
    })
  }

  private async ensureInitialized(): Promise<void> {
    if (this.initialized) return

    await this.call('initialize', {
      clientInfo: {
        name: 'codex-web-local',
        version: '0.1.0',
      },
    })

    this.initialized = true
  }

  async rpc(method: string, params: unknown): Promise<unknown> {
    await this.ensureInitialized()
    return this.call(method, params)
  }

  onNotification(listener: (value: SequencedNotification) => void): () => void {
    this.notificationListeners.add(listener)
    return () => {
      this.notificationListeners.delete(listener)
    }
  }

  getNotificationSequence(): number {
    return this.notificationSequence
  }

  listNotificationsAfter(sequence: number): SequencedNotification[] {
    return this.notificationHistory.filter((notification) => notification.sequence > sequence)
  }

  async respondToServerRequest(payload: unknown): Promise<void> {
    await this.ensureInitialized()

    const body = asRecord(payload)
    if (!body) {
      throw new Error('Invalid response payload: expected object')
    }

    const id = body.id
    if (typeof id !== 'number' || !Number.isInteger(id)) {
      throw new Error('Invalid response payload: "id" must be an integer')
    }

    const rawError = asRecord(body.error)
    if (rawError) {
      const message = typeof rawError.message === 'string' && rawError.message.trim().length > 0
        ? rawError.message.trim()
        : 'Server request rejected by client'
      const code = typeof rawError.code === 'number' && Number.isFinite(rawError.code)
        ? Math.trunc(rawError.code)
        : -32000
      this.resolvePendingServerRequest(id, { error: { code, message } })
      return
    }

    if (!('result' in body)) {
      throw new Error('Invalid response payload: expected "result" or "error"')
    }

    this.resolvePendingServerRequest(id, { result: body.result })
  }

  listPendingServerRequests(): PendingServerRequest[] {
    return Array.from(this.pendingServerRequests.values())
  }

  dispose(): void {
    if (!this.process) return

    const proc = this.process
    this.stopping = true
    this.process = null
    this.initialized = false
    this.readBuffer = ''

    const failure = new Error('codex app-server stopped')
    for (const request of this.pending.values()) {
      clearTimeout(request.timeout)
      request.reject(failure)
    }
    this.pending.clear()
    this.pendingServerRequests.clear()

    try {
      proc.stdin.end()
    } catch {
      // ignore close errors on shutdown
    }

    try {
      proc.kill('SIGTERM')
    } catch {
      // ignore kill errors on shutdown
    }

    const forceKillTimer = setTimeout(() => {
      if (!proc.killed) {
        try {
          proc.kill('SIGKILL')
        } catch {
          // ignore kill errors on shutdown
        }
      }
    }, 1500)
    forceKillTimer.unref()
  }
}

function extractThreadId(value: unknown): string {
  return normalizeText(asRecord(asRecord(value)?.thread)?.id)
}

function extractCompletedTurnText(value: unknown): string {
  const record = asRecord(value)
  const turn = asRecord(record?.turn) ?? record
  const items = Array.isArray(turn?.items) ? turn.items : []
  return items
    .map(asRecord)
    .filter((item) => normalizeText(item?.type) === 'agentMessage')
    .map((item) => normalizeText(item?.text))
    .filter(Boolean)
    .join('\n')
    .trim()
}

async function waitForProviderRuntimeStatus(
  configId: string,
  providerId: string,
  model: string,
  startedAtMs: number,
): Promise<Record<string, unknown>> {
  for (let attempt = 0; attempt < 50; attempt++) {
    const runtime = await readJsonFile(codexProviderRuntimeStatusPath)
    const status = asRecord(asRecord(runtime?.providers)?.[configId])
    const checkedAtMs = Date.parse(normalizeText(status?.checkedAt))
    if (
      status &&
      Number.isFinite(checkedAtMs) &&
      checkedAtMs >= startedAtMs &&
      normalizeText(status.providerId) === providerId &&
      normalizeText(status.requestedModel) === model
    ) {
      if (status.success !== true) {
        throw new Error(`Provider runtime request failed: ${normalizeText(status.error) || 'unknown error'}`)
      }
      const reportedModel = normalizeText(status.reportedModel)
      if (reportedModel && reportedModel !== model) {
        throw new Error(`Provider runtime reported unexpected model: ${reportedModel}`)
      }
      return status
    }
    await sleepMs(100)
  }
  throw new Error('Provider runtime verification status was not observed')
}

async function verifyCodexProviderEndToEnd(
  appServer: AppServerProcess,
  providerId: string,
  model: string,
): Promise<Record<string, unknown>> {
  const routeInstruction = `This is a host routing verification. Follow each verification prompt exactly. The host selected provider_id=${providerId} model=${model}.`
  const started = await appServer.rpc('thread/start', {
    model,
    modelProvider: providerId,
    approvalPolicy: 'never',
    sandbox: 'read-only',
    ephemeral: true,
    developerInstructions: routeInstruction,
  })
  const threadId = extractThreadId(started)
  if (!threadId) throw new Error('Codex thread/start did not return a thread id')

  try {
    const assistantTexts: string[] = []
    for (const expected of ['POCKET_LOBSTER_ROUTE_OK_1', 'POCKET_LOBSTER_ROUTE_OK_2']) {
      let resolveCompleted: (value: unknown) => void = () => undefined
      let rejectCompleted: (reason?: unknown) => void = () => undefined
      const completed = new Promise<unknown>((resolve, reject) => {
        resolveCompleted = resolve
        rejectCompleted = reject
      })
      const stopListening = appServer.onNotification((notification) => {
        if (notification.method !== 'turn/completed') return
        const params = asRecord(notification.params)
        if (normalizeText(params?.threadId) !== threadId) return
        resolveCompleted(notification.params)
      })
      const timeout = setTimeout(
        () => rejectCompleted(new Error('Codex provider two-turn test timed out')),
        120_000,
      )
      try {
        await appServer.rpc('turn/start', {
          threadId,
          input: [{ type: 'text', text: `Reply with exactly ${expected}.` }],
          model,
          effort: 'medium',
        })
        const completedPayload = await completed
        let assistantText = extractCompletedTurnText(completedPayload)
        if (!assistantText) {
          const read = await appServer.rpc('thread/read', { threadId, includeTurns: true })
          const thread = asRecord(asRecord(read)?.thread)
          const turns = Array.isArray(thread?.turns) ? thread.turns : []
          assistantText = turns.map(extractCompletedTurnText).filter(Boolean).join('\n').trim()
        }
        if (!assistantText.includes(expected)) {
          throw new Error(`Codex route test returned unexpected content: ${assistantText.slice(0, 240) || 'empty'}`)
        }
        assistantTexts.push(assistantText)
      } finally {
        clearTimeout(timeout)
        stopListening()
      }
    }
    return { ok: true, threadId, providerId, model, assistantTexts, turnsVerified: 2 }
  } finally {
    try { await appServer.rpc('thread/unsubscribe', { threadId }) } catch { /* best effort */ }
  }
}

class MethodCatalog {
  private methodCache: string[] | null = null
  private notificationCache: string[] | null = null

  private async runGenerateSchemaCommand(outDir: string): Promise<void> {
    await new Promise<void>((resolve, reject) => {
      const codexBin = prefixBin ? join(prefixBin, 'codex') : 'codex'
      const process = spawn(codexBin, ['app-server', 'generate-json-schema', '--out', outDir], {
        stdio: ['ignore', 'ignore', 'pipe'],
      })

      let stderr = ''

      process.stderr.setEncoding('utf8')
      process.stderr.on('data', (chunk: string) => {
        stderr += chunk
      })

      process.on('error', reject)
      process.on('exit', (code) => {
        if (code === 0) {
          resolve()
          return
        }

        reject(new Error(stderr.trim() || `generate-json-schema exited with code ${String(code)}`))
      })
    })
  }

  private extractMethodsFromClientRequest(payload: unknown): string[] {
    const root = asRecord(payload)
    const oneOf = Array.isArray(root?.oneOf) ? root.oneOf : []
    const methods = new Set<string>()

    for (const entry of oneOf) {
      const row = asRecord(entry)
      const properties = asRecord(row?.properties)
      const methodDef = asRecord(properties?.method)
      const methodEnum = Array.isArray(methodDef?.enum) ? methodDef.enum : []

      for (const item of methodEnum) {
        if (typeof item === 'string' && item.length > 0) {
          methods.add(item)
        }
      }
    }

    return Array.from(methods).sort((a, b) => a.localeCompare(b))
  }

  private extractMethodsFromServerNotification(payload: unknown): string[] {
    const root = asRecord(payload)
    const oneOf = Array.isArray(root?.oneOf) ? root.oneOf : []
    const methods = new Set<string>()

    for (const entry of oneOf) {
      const row = asRecord(entry)
      const properties = asRecord(row?.properties)
      const methodDef = asRecord(properties?.method)
      const methodEnum = Array.isArray(methodDef?.enum) ? methodDef.enum : []

      for (const item of methodEnum) {
        if (typeof item === 'string' && item.length > 0) {
          methods.add(item)
        }
      }
    }

    return Array.from(methods).sort((a, b) => a.localeCompare(b))
  }

  async listMethods(): Promise<string[]> {
    if (this.methodCache) {
      return this.methodCache
    }

    const outDir = await mkdtemp(join(tmpdir(), 'codex-web-local-schema-'))
    await this.runGenerateSchemaCommand(outDir)

    const clientRequestPath = join(outDir, 'ClientRequest.json')
    const raw = await readFile(clientRequestPath, 'utf8')
    const parsed = JSON.parse(raw) as unknown
    const methods = this.extractMethodsFromClientRequest(parsed)

    this.methodCache = methods
    return methods
  }

  async listNotificationMethods(): Promise<string[]> {
    if (this.notificationCache) {
      return this.notificationCache
    }

    const outDir = await mkdtemp(join(tmpdir(), 'codex-web-local-schema-'))
    await this.runGenerateSchemaCommand(outDir)

    const serverNotificationPath = join(outDir, 'ServerNotification.json')
    const raw = await readFile(serverNotificationPath, 'utf8')
    const parsed = JSON.parse(raw) as unknown
    const methods = this.extractMethodsFromServerNotification(parsed)

    this.notificationCache = methods
    return methods
  }
}

type CodexBridgeMiddleware = ((req: IncomingMessage, res: ServerResponse, next: () => void) => Promise<void>) & {
  dispose: () => void
}

type SharedBridgeState = {
  appServer: AppServerProcess
  methodCatalog: MethodCatalog
}

const SHARED_BRIDGE_KEY = '__codexRemoteSharedBridge__'

function getSharedBridgeState(): SharedBridgeState {
  const globalScope = globalThis as typeof globalThis & {
    [SHARED_BRIDGE_KEY]?: SharedBridgeState
  }

  const existing = globalScope[SHARED_BRIDGE_KEY]
  if (existing) return existing

  const created: SharedBridgeState = {
    appServer: new AppServerProcess(),
    methodCatalog: new MethodCatalog(),
  }
  globalScope[SHARED_BRIDGE_KEY] = created
  return created
}

function providerDefinitionForConfig(config: Record<string, unknown>): Record<string, unknown> | null {
  const configId = normalizeText(config.id)
  const displayName = normalizeText(config.displayName)
  if (!configId) return null
  const configuredPort = Number.parseInt(process.env.CODEX_WEB_LOCAL_PORT ?? '', 10)
  const localPort = Number.isInteger(configuredPort) && configuredPort > 0 && configuredPort <= 65_535
    ? configuredPort
    : 3000
  return {
    name: displayName || normalizeText(config.modelId) || configId,
    base_url: `http://127.0.0.1:${localPort}/codex-provider-adapter/${encodeURIComponent(configId)}/v1`,
    wire_api: 'responses',
    env_key: `POCKET_LOBSTER_CODEX_${configId.toUpperCase().replace(/[^A-Z0-9_]/gu, '_')}_API_KEY`,
    requires_openai_auth: false,
  }
}

async function ensureCodexProviderDefinitions(appServer: AppServerProcess): Promise<number> {
  const catalog = await readJsonFile(codexModelProvidersPath)
  const configs = Array.isArray(catalog?.configs) ? catalog.configs.map(asRecord).filter(Boolean) : []
  const edits: Record<string, unknown>[] = []
  for (const config of configs) {
    if (!config) continue
    const providerId = normalizeText(config.providerId)
    const definition = providerDefinitionForConfig(config)
    if (!providerId || !definition) continue
    edits.push({ keyPath: `model_providers.${providerId}`, value: definition, mergeStrategy: 'upsert' })
  }
  if (edits.length > 0) {
    await appServer.rpc('config/batchWrite', { edits })
  }
  return edits.length
}

async function updateCatalogSelection(providerId: string, model: string): Promise<void> {
  if (!codexModelProvidersPath) return
  const catalog = await readJsonFile(codexModelProvidersPath)
  if (!catalog) return
  const configs = Array.isArray(catalog.configs) ? catalog.configs : []
  let currentConfigId = ''
  for (const item of configs) {
    const row = asRecord(item)
    if (!row) continue
    const selected = normalizeText(row.providerId) === providerId
    row.isDefault = selected
    if (selected) {
      currentConfigId = normalizeText(row.id)
      row.modelId = model
    }
  }
  catalog.currentConfigId = currentConfigId
  await writeJsonFileAtomic(codexModelProvidersPath, catalog)
}

let providerModelRefreshAt = 0
let providerModelRefreshPromise: Promise<number> | null = null

async function refreshStoredProviderModels(): Promise<number> {
  const now = Date.now()
  if (now - providerModelRefreshAt < PROVIDER_MODEL_REFRESH_INTERVAL_MS) return 0
  if (providerModelRefreshPromise) return providerModelRefreshPromise
  providerModelRefreshPromise = (async () => {
    const catalog = await readJsonFile(codexModelProvidersPath)
    if (!catalog) return 0
    const configs = Array.isArray(catalog.configs) ? catalog.configs : []
    let changed = 0
    for (const item of configs) {
      const row = asRecord(item)
      if (!row) continue
      const configId = normalizeText(row.id)
      const baseUrl = normalizeText(row.baseUrl).replace(/\/+$/u, '')
      if (!configId || !baseUrl) continue
      const envKey = `POCKET_LOBSTER_CODEX_${configId.toUpperCase().replace(/[^A-Z0-9_]/gu, '_')}_API_KEY`
      const apiKey = normalizeText(process.env[envKey])
      if (!apiKey) continue
      try {
        const response = await fetch(`${baseUrl}/models`, {
          headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
          signal: AbortSignal.timeout(12_000),
        })
        if (!response.ok) continue
        const payload = asRecord(await response.json() as unknown)
        const data = Array.isArray(payload?.data) ? payload.data : []
        const modelIds = Array.from(new Set(data.map(asRecord).map((model) => normalizeText(model?.id)).filter(Boolean)))
        const configuredModel = normalizeText(row.modelId)
        if (configuredModel && !modelIds.includes(configuredModel)) modelIds.unshift(configuredModel)
        if (modelIds.length === 0) continue
        const previous = Array.isArray(row.availableModelIds)
          ? row.availableModelIds.map(normalizeText).filter(Boolean)
          : configuredModel ? [configuredModel] : []
        if (JSON.stringify(previous) !== JSON.stringify(modelIds)) {
          row.availableModelIds = modelIds
          changed += 1
        }
      } catch {
        // Keep the last known model catalog when the provider is temporarily unavailable.
      }
    }
    if (changed > 0) await writeJsonFileAtomic(codexModelProvidersPath, catalog)
    providerModelRefreshAt = Date.now()
    return changed
  })().finally(() => {
    providerModelRefreshPromise = null
  })
  return providerModelRefreshPromise
}

async function validateCodexProviderModel(providerId: string, model: string): Promise<void> {
  if (providerId === 'openai') return
  const catalog = await readJsonFile(codexModelProvidersPath)
  const configs = Array.isArray(catalog?.configs) ? catalog.configs.map(asRecord).filter(Boolean) : []
  const selected = configs.find((row) => normalizeText(row?.providerId) === providerId)
  if (!selected) throw new Error(`Provider configuration not found: ${providerId}`)
  const modelIds = new Set([
    normalizeText(selected.modelId),
    ...(Array.isArray(selected.availableModelIds) ? selected.availableModelIds.map(normalizeText) : []),
  ].filter(Boolean))
  if (!modelIds.has(model)) throw new Error(`Model ${model} is not available from provider ${providerId}`)
}

async function selectCodexModel(
  appServer: AppServerProcess,
  providerId: string,
  model: string,
): Promise<Record<string, unknown>> {
  if (!providerId || !model) throw new Error('Missing providerId or model')
  await validateCodexProviderModel(providerId, model)
  await ensureCodexProviderDefinitions(appServer)
  await appServer.rpc('config/batchWrite', {
    edits: [
      { keyPath: 'model_provider', value: providerId, mergeStrategy: 'replace' },
      { keyPath: 'model', value: model, mergeStrategy: 'replace' },
    ],
  })
  await updateCatalogSelection(providerId, model)
  return { ok: true, providerId, model }
}

async function switchCodexThreadRoute(
  appServer: AppServerProcess,
  threadId: string,
  providerId: string,
  model: string,
): Promise<Record<string, unknown>> {
  if (!threadId || !providerId || !model) throw new Error('Missing threadId, providerId or model')
  await appendCodexDiagnostic('route_switch_request', { threadId, providerId, model })
  await validateCodexProviderModel(providerId, model)
  await ensureCodexProviderDefinitions(appServer)
  let previousProvider = ''
  try {
    const read = asRecord(await appServer.rpc('thread/read', { threadId, includeTurns: false }))
    previousProvider = normalizeText(asRecord(read?.thread)?.modelProvider)
  } catch {
    previousProvider = ''
  }
  try {
    await appServer.rpc('thread/unsubscribe', { threadId })
  } catch {
    // A persisted thread may not currently have an active subscription.
  }

  let migration: PersistedThreadRouteMigration | null = null
  if (previousProvider && previousProvider !== providerId) {
    appServer.dispose()
    migration = await migratePersistedThreadRoute(threadId, providerId)
  }

  try {
    await ensureCodexProviderDefinitions(appServer)
    const routeParams: Record<string, unknown> = { threadId, model, modelProvider: providerId }
    const injected = await buildInjectedDeveloperInstructions(routeParams)
    if (injected) routeParams.developerInstructions = injected
    const resumed = asRecord(await appServer.rpc('thread/resume', routeParams))
    const actualProvider = normalizeText(asRecord(resumed?.thread)?.modelProvider)
    if (actualProvider && actualProvider !== providerId) {
      throw new Error(`Codex route mismatch: expected ${providerId}, received ${actualProvider}`)
    }
    await appendCodexDiagnostic('route_switch_success', {
      threadId,
      providerId: actualProvider || providerId,
      model,
      previousProvider,
      sanitizedReasoningItems: migration?.sanitizedReasoningItems ?? 0,
      removedCompactionItems: migration?.removedCompactionItems ?? 0,
    })
    return {
      ok: true,
      providerId: actualProvider || providerId,
      model,
      previousProvider,
      sanitizedReasoningItems: migration?.sanitizedReasoningItems ?? 0,
      removedCompactionItems: migration?.removedCompactionItems ?? 0,
      thread: resumed?.thread,
    }
  } catch (error) {
    if (migration) {
      appServer.dispose()
      await restorePersistedThreadRoute(migration)
    }
    await appendCodexDiagnostic('route_switch_failure', {
      threadId,
      providerId,
      model,
      previousProvider,
      error: getErrorMessage(error, 'Unknown route switch error').slice(0, 800),
    })
    throw error
  }
}

function normalizeCollaborationAgent(value: unknown): CollaborationAgentId {
  const normalized = normalizeText(value).toLowerCase()
  if (normalized === 'claude' || normalized === 'claude-code') return 'claude'
  if (normalized === 'minis' || normalized === 'openminis') return 'minis'
  return 'codex'
}

function createCollaborationAgentState(
  agentId: CollaborationAgentId,
  leader: CollaborationAgentId,
): CollaborationAgentState {
  return {
    agentId,
    role: agentId === leader ? 'leader' : 'worker',
    status: 'idle',
    sessionId: '',
    runId: '',
    turnId: '',
    startedAtMs: 0,
    updatedAtMs: Date.now(),
    requestText: '',
    assignmentText: '',
    actionText: '',
    responseText: '',
    errorText: '',
  }
}

function isCollaborationRunActive(run: CollaborationRun): boolean {
  return run.status === 'planning' || run.status === 'running' || run.status === 'reviewing'
}

function addCollaborationEvent(
  run: CollaborationRun,
  type: CollaborationEventType,
  text: string,
  agentId: CollaborationAgentId | '' = '',
): void {
  const normalized = normalizeText(text)
  if (!normalized) return
  run.events.push({
    id: `event_${Date.now().toString(36)}_${randomUUID().slice(0, 6)}`,
    atMs: Date.now(),
    type,
    agentId,
    text: normalized,
  })
  if (run.events.length > 240) run.events.splice(0, run.events.length - 240)
  touchCollaborationRun(run)
}

function collaborationAgentRecord(leader: CollaborationAgentId): Record<CollaborationAgentId, CollaborationAgentState> {
  return {
    codex: createCollaborationAgentState('codex', leader),
    claude: createCollaborationAgentState('claude', leader),
    minis: createCollaborationAgentState('minis', leader),
  }
}

async function ensureCollaborationRunsLoaded(): Promise<void> {
  if (collaborationRunsLoaded) return
  collaborationRunsLoaded = true
  let restoredInterruptedRun = false
  try {
    const parsed = JSON.parse(await readFile(COLLABORATION_STATE_PATH, 'utf8')) as unknown
    const record = asRecord(parsed)
    const rows = Array.isArray(record?.runs) ? record.runs : []
    for (const value of rows) {
      const row = asRecord(value)
      const id = normalizeText(row?.id)
      const prompt = normalizeText(row?.prompt)
      if (!id || !prompt) continue
      const leader = normalizeCollaborationAgent(row?.leader)
      const sourceAgents = asRecord(row?.agents)
      const agents = collaborationAgentRecord(leader)
      for (const agentId of ['codex', 'claude', 'minis'] as const) {
        const source = asRecord(sourceAgents?.[agentId])
        if (!source) continue
        agents[agentId] = {
          ...agents[agentId],
          status: normalizeText(source.status) as CollaborationAgentStatus || 'failed',
          sessionId: normalizeText(source.sessionId),
          runId: normalizeText(source.runId),
          turnId: normalizeText(source.turnId),
          startedAtMs: typeof source.startedAtMs === 'number' ? source.startedAtMs : 0,
          updatedAtMs: typeof source.updatedAtMs === 'number' ? source.updatedAtMs : Date.now(),
          requestText: normalizeText(source.requestText),
          assignmentText: normalizeText(source.assignmentText),
          actionText: normalizeText(source.actionText),
          responseText: normalizeText(source.responseText),
          errorText: normalizeText(source.errorText),
        }
        if (['planning', 'running', 'reviewing'].includes(agents[agentId].status)) {
          agents[agentId].status = 'failed'
          agents[agentId].errorText = '应用重启导致该协作步骤中断'
          restoredInterruptedRun = true
        }
      }
      const restoredStatus = normalizeText(row?.status)
      const wasInterrupted = ['planning', 'running', 'reviewing'].includes(restoredStatus)
      const sourceEvents = Array.isArray(row?.events) ? row.events : []
      const events = sourceEvents.map((value): CollaborationEvent | null => {
        const event = asRecord(value)
        const type = normalizeText(event?.type) as CollaborationEventType
        const text = normalizeText(event?.text)
        if (!text || !['user', 'decision', 'assignment', 'progress', 'result', 'final', 'error', 'control'].includes(type)) return null
        const eventAgent = normalizeText(event?.agentId)
        return {
          id: normalizeText(event?.id) || `event_${randomUUID().slice(0, 8)}`,
          atMs: typeof event?.atMs === 'number' ? event.atMs : Date.now(),
          type,
          agentId: eventAgent === 'codex' || eventAgent === 'claude' || eventAgent === 'minis' ? eventAgent : '',
          text,
        }
      }).filter((value): value is CollaborationEvent => value !== null)
      collaborationRuns.set(id, {
        id,
        title: normalizeText(row?.title) || `三智能体协作 ${id.slice(-6)}`,
        prompt,
        leader,
        status: wasInterrupted
          ? 'failed'
          : (restoredStatus as CollaborationRunStatus || 'failed'),
        createdAtMs: typeof row?.createdAtMs === 'number' ? row.createdAtMs : Date.now(),
        updatedAtMs: typeof row?.updatedAtMs === 'number' ? row.updatedAtMs : Date.now(),
        completedAtMs: typeof row?.completedAtMs === 'number' ? row.completedAtMs : null,
        turnNumber: typeof row?.turnNumber === 'number' ? row.turnNumber : 1,
        roundNumber: typeof row?.roundNumber === 'number' ? row.roundNumber : 0,
        archived: row?.archived === true,
        workspaceReady: row?.workspaceReady === true,
        pendingUserMessages: !wasInterrupted && Array.isArray(row?.pendingUserMessages)
          ? row.pendingUserMessages.map(normalizeText).filter(Boolean)
          : [],
        events,
        agents,
        finalSummary: normalizeText(row?.finalSummary),
        errorText: wasInterrupted
          ? '应用重启导致协作中断'
          : normalizeText(row?.errorText),
      })
    }
    if (restoredInterruptedRun) await persistCollaborationRunsNow()
  } catch {
    // First run has no state file.
  }
}

async function persistCollaborationRunsNow(): Promise<void> {
  const persist = collaborationPersistQueue.then(async () => {
    const runs = [...collaborationRuns.values()]
      .sort((a, b) => b.createdAtMs - a.createdAtMs)
      .slice(0, COLLABORATION_HISTORY_LIMIT)
    await mkdir(dirname(COLLABORATION_STATE_PATH), { recursive: true })
    const temporary = `${COLLABORATION_STATE_PATH}.${process.pid}.tmp`
    await writeFile(temporary, JSON.stringify({ version: 1, runs }, null, 2), { mode: 0o600 })
    await rename(temporary, COLLABORATION_STATE_PATH)
  })
  collaborationPersistQueue = persist.catch(() => undefined)
  await persist
}

function scheduleCollaborationPersist(delayMs = 120): void {
  if (collaborationPersistTimer !== null) return
  collaborationPersistTimer = setTimeout(() => {
    collaborationPersistTimer = null
    void persistCollaborationRunsNow()
  }, delayMs)
}

function touchCollaborationRun(run: CollaborationRun): void {
  run.updatedAtMs = Date.now()
  collaborationRuns.set(run.id, run)
  scheduleCollaborationPersist()
}

function setCollaborationAgentState(
  run: CollaborationRun,
  agentId: CollaborationAgentId,
  patch: Partial<CollaborationAgentState>,
): CollaborationAgentState {
  const next = {
    ...run.agents[agentId],
    ...patch,
    updatedAtMs: Date.now(),
  }
  run.agents[agentId] = next
  touchCollaborationRun(run)
  return next
}

function isCollaborationRunAborted(run: CollaborationRun): boolean {
  return collaborationRuns.get(run.id)?.status === 'aborted'
}

async function callMinisChatRpc(method: string, params: Record<string, unknown>): Promise<Record<string, unknown>> {
  if (!SHARED_BRIDGE_TOKEN_PATH) throw new Error('Shared bridge token path is unavailable')
  const token = (await readFile(SHARED_BRIDGE_TOKEN_PATH, 'utf8')).trim()
  if (!token) throw new Error('Shared bridge token is empty')
  const response = await fetch(`${MINIS_BRIDGE_URL}/chat/rpc`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'X-Pocket-Lobster-Token': token,
    },
    body: JSON.stringify({ jsonrpc: '2.0', id: Date.now(), method, params }),
  })
  const payload = asRecord(await response.json().catch(() => null))
  if (!response.ok) throw new Error(normalizeText(payload?.error) || `Minis bridge HTTP ${response.status}`)
  const rpcError = asRecord(payload?.error)
  if (rpcError) throw new Error(normalizeText(rpcError.message) || 'Minis RPC failed')
  return asRecord(payload?.result) ?? {}
}

function collaborationWorkspacePath(run: CollaborationRun): string {
  return join(COLLABORATION_SHARED_ROOT, run.id)
}

async function prepareCollaborationWorkspace(run: CollaborationRun): Promise<void> {
  if (run.workspaceReady) return
  const workspace = collaborationWorkspacePath(run)
  await mkdir(workspace, { recursive: true })
  const rules = [
    '口袋大龙虾三智能体协作工作区',
    '本目录只存放本轮任务明确要求共享的文件产物，不承担智能体消息通信。',
    '每个智能体只能写入以自身名称开头的临时文件，不允许并发改写同一目标。',
    '最终文件只能由总调度在审核成员结果后使用临时文件原子替换。',
  ].join('\n')
  await writeFile(join(workspace, '协作规则.txt'), `${rules}\n`, { mode: 0o600 })
  run.workspaceReady = true
  touchCollaborationRun(run)
}

function collaborationAgentLabel(agentId: CollaborationAgentId): string {
  return agentId === 'codex' ? 'Codex' : agentId === 'claude' ? 'Claude Code' : 'Minis'
}

function collaborationPublicHistory(run: CollaborationRun): string {
  const rows = run.events
    .filter((event) => event.type === 'user' || event.type === 'final')
    .slice(-8)
    .map((event) => `${event.type === 'user' ? '用户' : collaborationAgentLabel(run.leader)}：${event.text}`)
  return rows.length > 0 ? rows.join('\n\n') : '无历史消息'
}

function buildCollaborationCoordinatorPrompt(run: CollaborationRun, userMessage: string): string {
  const workers = (['codex', 'claude', 'minis'] as const)
    .filter((agentId) => agentId !== run.leader)
    .map((agentId) => collaborationAgentLabel(agentId))
    .join('、')
  return [
    '[口袋大龙虾三智能体协作：总调度决策]',
    `您是用户当前选择的总调度${collaborationAgentLabel(run.leader)}，您始终负责与用户对话和最终回复。可按需调用的协作成员是${workers}。`,
    '先判断这条消息是否真的需要其他智能体。问候、连通性确认、简单问答、澄清和单智能体即可完成的任务必须直接回复，不得委派，不得创建文件。',
    '只有任务能够拆成明确且有价值的专门子任务时才委派；只选择确实需要的成员，不要默认全选。成员不会直接面对用户，您必须审核其结果并最终回复。',
    '共享工作区默认关闭。只有用户明确要求共同产出文件，或多个智能体确实必须交换文件产物时，requiresSharedWorkspace才可为true。',
    '请只输出一个JSON对象，不要输出Markdown、解释或代码围栏。格式为：',
    '{"action":"respond或ask_user或delegate","message":"直接回复、澄清问题或您的阶段判断","requiresSharedWorkspace":false,"assignments":[{"agentId":"codex或claude或minis","task":"边界清晰的子任务","expectedOutput":"预期结果"}]}',
    'respond和ask_user时assignments必须为空；delegate时不得把任务分配给您自己。',
    '',
    '本协作会话公开历史：',
    collaborationPublicHistory(run),
    '',
    '用户本轮消息：',
    userMessage,
  ].join('\n')
}

function buildCollaborationWorkerPrompt(
  run: CollaborationRun,
  assignment: CollaborationAssignment,
  userMessage: string,
): string {
  const workspaceInstruction = run.workspaceReady
    ? `本轮已启用共享文件产物目录：${collaborationWorkspacePath(run)}。只在子任务确有文件产物时使用，并且只能写入以${assignment.agentId}开头的临时文件。`
    : '本轮未启用共享文件产物目录，不要创建协作草稿或自行寻找共享目录。'
  return [
    '[口袋大龙虾三智能体协作：成员分工]',
    `总调度：${collaborationAgentLabel(run.leader)}`,
    `当前成员：${collaborationAgentLabel(assignment.agentId)}`,
    '您只负责下面的边界化子任务。完成后把事实、执行结果、证据、风险和建议直接回复给总调度，不要向用户讲话，不要等待其他成员，不要轮询协作状态文件。',
    `子任务：${assignment.task}`,
    `预期结果：${assignment.expectedOutput || '给出可核查的完整结果'}`,
    workspaceInstruction,
    '',
    '用户原始消息仅作为背景，不代表要求您重复执行全部任务：',
    userMessage,
  ].join('\n')
}

function extractCollaborationJson(value: string): Record<string, unknown> | null {
  const trimmed = value.trim()
  const candidates = [trimmed]
  const fenced = trimmed.match(/```(?:json)?\s*([\s\S]*?)```/iu)?.[1]
  if (fenced) candidates.push(fenced.trim())
  const firstBrace = trimmed.indexOf('{')
  const lastBrace = trimmed.lastIndexOf('}')
  if (firstBrace >= 0 && lastBrace > firstBrace) candidates.push(trimmed.slice(firstBrace, lastBrace + 1))
  for (const candidate of candidates) {
    try {
      const parsed = asRecord(JSON.parse(candidate))
      if (parsed) return parsed
    } catch {
      // Try the next representation.
    }
  }
  return null
}

function parseCollaborationDecision(
  value: string,
  leader: CollaborationAgentId,
): CollaborationDecision | null {
  const parsed = extractCollaborationJson(value)
  if (!parsed) return null
  const actionRaw = normalizeText(parsed.action).toLowerCase()
  const action = actionRaw === 'respond' || actionRaw === '回复' || actionRaw === '直接回复'
    ? 'respond'
    : actionRaw === 'ask_user' || actionRaw === 'ask-user' || actionRaw === '澄清' || actionRaw === '询问用户'
      ? 'ask_user'
      : actionRaw === 'delegate' || actionRaw === '委派' || actionRaw === '分工'
        ? 'delegate'
        : ''
  if (action !== 'respond' && action !== 'ask_user' && action !== 'delegate') return null
  const assignmentRows = Array.isArray(parsed.assignments)
    ? parsed.assignments
    : Array.isArray(parsed.tasks)
      ? parsed.tasks
      : []
  const assignments = assignmentRows
    .map((row): CollaborationAssignment | null => {
      const record = asRecord(row)
      const rawAgentId = normalizeText(record?.agentId || record?.agent).toLowerCase()
      const agentId = rawAgentId === 'claude code' || rawAgentId === 'claudecode'
        ? 'claude'
        : rawAgentId
      const task = normalizeText(record?.task)
      if ((agentId !== 'codex' && agentId !== 'claude' && agentId !== 'minis') || agentId === leader || !task) return null
      return {
        agentId,
        task,
        expectedOutput: normalizeText(record?.expectedOutput),
      }
    })
    .filter((row): row is CollaborationAssignment => row !== null)
    .filter((row, index, rows) => rows.findIndex((candidate) => candidate.agentId === row.agentId) === index)
  if (action === 'delegate' && assignments.length === 0) return null
  return {
    action,
    message: normalizeText(parsed.message || parsed.reply || parsed.question),
    assignments: action === 'delegate' ? assignments : [],
    requiresSharedWorkspace: action === 'delegate' && parsed.requiresSharedWorkspace === true,
  }
}

function extractStartedTurnId(value: unknown): string {
  const record = asRecord(value)
  const turn = asRecord(record?.turn) ?? record
  return normalizeText(turn?.id)
}

function collaborationActionFromCodexTurn(value: unknown): string {
  const turn = asRecord(value)
  const items = Array.isArray(turn?.items) ? turn.items.map(asRecord).filter(Boolean) : []
  for (let index = items.length - 1; index >= 0; index -= 1) {
    const item = items[index]
    const type = normalizeText(item?.type)
    if (type === 'agentMessage') continue
    if (type === 'reasoning') {
      const text = normalizeText(item?.text) || normalizeText(item?.summary)
      return text ? truncateText(text, 500) : 'Codex 正在分析任务'
    }
    if (type === 'commandExecution') {
      return 'Codex 正在执行终端操作'
    }
    if (type === 'mcpToolCall') {
      const server = normalizeText(item?.server)
      const tool = normalizeText(item?.tool)
      return `Codex 正在调用工具：${[server, tool].filter(Boolean).join('/') || 'MCP工具'}`
    }
    if (type === 'fileChange') return 'Codex 正在修改文件'
    if (type) return `Codex 正在执行：${type}`
  }
  return 'Codex 正在处理协作任务'
}

async function waitForCodexCollaborationTurn(
  appServer: AppServerProcess,
  threadId: string,
  turnId: string,
  timeoutMs = COLLABORATION_TIMEOUT_MS,
): Promise<string> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const read = asRecord(await appServer.rpc('thread/read', { threadId, includeTurns: true }))
    const thread = asRecord(read?.thread)
    const turns = Array.isArray(thread?.turns) ? thread.turns : []
    const turn = turns.map(asRecord).find((row) => normalizeText(row?.id) === turnId)
      ?? asRecord(turns[turns.length - 1])
    const status = normalizeText(turn?.status)
    const actionText = collaborationActionFromCodexTurn(turn)
    const run = [...collaborationRuns.values()].find((row) => row.agents.codex.turnId === turnId)
    if (run?.status === 'aborted') throw new Error('Collaboration run aborted')
    if (run && run.agents.codex.actionText !== actionText) {
      setCollaborationAgentState(run, 'codex', { actionText })
    }
    if (status === 'completed') return extractCompletedTurnText(turn)
    if (status === 'failed' || status === 'interrupted' || status === 'cancelled') {
      throw new Error(normalizeText(asRecord(turn?.error)?.message) || `Codex turn ${status}`)
    }
    await sleepMs(900)
  }
  throw new Error('Codex collaboration turn timed out')
}

async function runCodexCollaborationTurn(
  appServer: AppServerProcess,
  run: CollaborationRun,
  prompt: string,
  timeoutMs = COLLABORATION_TIMEOUT_MS,
): Promise<string> {
  const state = run.agents.codex
  let threadId = state.sessionId

  const prepareThreadParams = async (params: Record<string, unknown>): Promise<Record<string, unknown>> => {
    await ensureCodexProviderDefinitions(appServer)
    const injected = await buildInjectedDeveloperInstructions(params)
    if (injected) {
      params.developerInstructions = mergeDeveloperInstructions(params.developerInstructions, injected)
    }
    return params
  }

  const startReplacementThread = async (replacingMissingThread: boolean): Promise<string> => {
    const params = await prepareThreadParams({
      cwd: homeDir || undefined,
      approvalPolicy: 'never',
      sandbox: 'danger-full-access',
      developerInstructions: 'This thread is part of Pocket Lobster three-agent collaboration. Preserve the collaboration run id and produce inspectable factual output.',
    })
    const started = await appServer.rpc('thread/start', params)
    const nextThreadId = extractThreadId(started)
    if (!nextThreadId) throw new Error('Codex collaboration thread/start returned no thread id')
    setCollaborationAgentState(run, 'codex', { sessionId: nextThreadId, turnId: '' })
    if (replacingMissingThread) {
      addCollaborationEvent(run, 'control', 'Codex历史会话已失效，宿主已自动创建替代会话并继续本轮任务', 'codex')
    }
    return nextThreadId
  }

  const recoverThread = async (missingThreadId: string): Promise<string> => {
    try {
      const params = await prepareThreadParams({ threadId: missingThreadId })
      const resumed = await appServer.rpc('thread/resume', params)
      const resumedId = extractThreadId(resumed) || missingThreadId
      setCollaborationAgentState(run, 'codex', { sessionId: resumedId, turnId: '' })
      return resumedId
    } catch (error) {
      if (!isMissingAgentSessionError(error, 'codex')) throw error
      return await startReplacementThread(true)
    }
  }

  if (threadId) {
    try {
      await appServer.rpc('thread/read', { threadId, includeTurns: false })
    } catch (error) {
      if (!isMissingAgentSessionError(error, 'codex')) throw error
      threadId = await recoverThread(threadId)
    }
  } else {
    threadId = await startReplacementThread(false)
  }

  let startedTurn: unknown
  try {
    startedTurn = await appServer.rpc('turn/start', {
      threadId,
      input: [{ type: 'text', text: prompt }],
    })
  } catch (error) {
    if (!isMissingAgentSessionError(error, 'codex')) throw error
    threadId = await recoverThread(threadId)
    startedTurn = await appServer.rpc('turn/start', {
      threadId,
      input: [{ type: 'text', text: prompt }],
    })
  }
  const turnId = extractStartedTurnId(startedTurn)
  if (!turnId) throw new Error('Codex collaboration turn/start returned no turn id')
  setCollaborationAgentState(run, 'codex', { turnId })
  return await waitForCodexCollaborationTurn(appServer, threadId, turnId, timeoutMs)
}

async function writeClaudeCollaborationMirror(
  sessionId: string,
  title: string,
  additions: Array<{ role: string; text: string; atMs: number }>,
): Promise<void> {
  if (!homeDir) return
  const directory = join(homeDir, '.pocketlobster', 'agent-sessions', 'claude-code')
  const safeId = sessionId.replace(/[^a-zA-Z0-9._-]/gu, '_')
  const target = join(directory, `${safeId}.json`)
  let existing: Record<string, unknown> = {}
  try {
    existing = asRecord(JSON.parse(await readFile(target, 'utf8')) as unknown) ?? {}
  } catch {
    // New collaboration session.
  }
  const previousMessages = Array.isArray(existing.messages) ? existing.messages : []
  const messages = [...previousMessages, ...additions]
  const updatedAtMs = additions[additions.length - 1]?.atMs ?? Date.now()
  const payload = {
    agentId: 'claude-code',
    sessionId,
    title,
    updatedAtMs,
    nativeSessionId: '',
    nativeSessionMode: 'collaboration_v1',
    messages,
  }
  await mkdir(directory, { recursive: true })
  const temporary = `${target}.${process.pid}.tmp`
  await writeFile(temporary, JSON.stringify(payload, null, 2), { mode: 0o600 })
  await rename(temporary, target)
}

async function waitForClaudeCollaborationRun(runId: string, timeoutMs = COLLABORATION_TIMEOUT_MS): Promise<string> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const status = await getClaudeRunStatus(runId)
    const collaborationRun = [...collaborationRuns.values()].find((row) => row.agents.claude.runId === runId)
    if (collaborationRun?.status === 'aborted') throw new Error('Collaboration run aborted')
    if (collaborationRun) {
      const actionText = collaborationRun.status === 'planning'
        ? 'Claude Code 正在判断是否需要委派'
        : collaborationRun.status === 'reviewing'
          ? 'Claude Code 正在审核成员结果'
          : 'Claude Code 正在执行分工任务'
      if (collaborationRun.agents.claude.actionText !== actionText) {
        setCollaborationAgentState(collaborationRun, 'claude', { actionText })
      }
    }
    if (status.completed === true) {
      if (status.status !== 'completed') throw new Error(normalizeText(status.error) || `Claude ${normalizeText(status.status)}`)
      return normalizeText(asRecord(status.result)?.assistantText)
    }
    await sleepMs(900)
  }
  throw new Error('Claude collaboration run timed out')
}

async function runClaudeCollaborationTurn(
  run: CollaborationRun,
  prompt: string,
  timeoutMs = COLLABORATION_TIMEOUT_MS,
): Promise<string> {
  const state = run.agents.claude
  const sessionId = state.sessionId || `collab-claude-${run.id}`
  const titleSeed = run.prompt.replace(/\s+/gu, ' ').trim().slice(0, 42)
  const title = titleSeed ? `三智能体协作：${titleSeed}` : '三智能体协作'
  let rebuiltMissingHistory = false
  if (!state.sessionId) {
    setCollaborationAgentState(run, 'claude', { sessionId })
  } else {
    const storedState = await readClaudeState()
    if (!storedState.sessions.some((session) => session.key === sessionId)) {
      rebuiltMissingHistory = true
    }
  }
  const isCoordinatorInternal = prompt.startsWith('[口袋大龙虾三智能体协作：总调度')
  const isCoordinatorReview = prompt.startsWith('[口袋大龙虾三智能体协作：总调度审核]')
  if (!isCoordinatorReview) {
    await writeClaudeCollaborationMirror(sessionId, title, [{ role: 'user', text: run.prompt, atMs: Date.now() }])
  }
  const started = await sendClaudeMessage({
    sessionKey: sessionId,
    message: prompt,
    allowSharedStorage: true,
    dangerousMode: true,
  })
  if (rebuiltMissingHistory) {
    addCollaborationEvent(run, 'control', 'Claude Code本地协作历史缺失，宿主已按当前协作公开历史重建并继续本轮任务', 'claude')
  }
  setCollaborationAgentState(run, 'claude', { runId: started.runId })
  const response = await waitForClaudeCollaborationRun(started.runId, timeoutMs)
  if (!isCoordinatorInternal) {
    await writeClaudeCollaborationMirror(sessionId, title, [{ role: 'assistant', text: response || 'Claude 未返回文本结果', atMs: Date.now() }])
  }
  return response
}

async function runMinisCollaborationTurn(
  run: CollaborationRun,
  prompt: string,
  timeoutMs = COLLABORATION_TIMEOUT_MS,
): Promise<string> {
  const state = run.agents.minis
  const titleSeed = run.prompt.replace(/\s+/gu, ' ').trim().slice(0, 42)
  let sessionId = state.sessionId
  let replacedMissingSession = false
  if (sessionId) {
    try {
      await callMinisChatRpc('chat.sessions.get', { sessionId })
    } catch (error) {
      if (!isMissingAgentSessionError(error, 'minis')) throw error
      sessionId = ''
      replacedMissingSession = true
    }
  }
  const promptParams = (): Record<string, unknown> => ({
    ...(sessionId ? { sessionId } : {}),
    prompt,
    sessionTitle: titleSeed ? `三智能体协作：${titleSeed}` : '三智能体协作',
    wait: false,
  })
  let started: Record<string, unknown>
  try {
    started = await callMinisChatRpc('chat.prompt', promptParams())
  } catch (error) {
    if (!sessionId || !isMissingAgentSessionError(error, 'minis')) throw error
    sessionId = ''
    replacedMissingSession = true
    started = await callMinisChatRpc('chat.prompt', promptParams())
  }
  const startedStatus = normalizeText(started.status).toLowerCase()
  if (startedStatus === 'error' || startedStatus === 'failed') {
    throw new Error(normalizeText(started.responseText) || `Minis collaboration prompt ${startedStatus}`)
  }
  sessionId = normalizeText(started.sessionId)
  if (!sessionId) throw new Error('Minis collaboration prompt returned no session id')
  setCollaborationAgentState(run, 'minis', { sessionId, actionText: 'Minis 已收到任务，正在执行' })
  if (replacedMissingSession) {
    addCollaborationEvent(run, 'control', 'Minis历史会话已失效，宿主已自动创建替代会话并继续本轮任务', 'minis')
  }
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (run.status === 'aborted') throw new Error('Collaboration run aborted')
    const status = await callMinisChatRpc('chat.session.status', { sessionId })
    const running = status.isRunning === true
    const lastRole = normalizeText(status.lastMessageRole)
    const actionText = running
      ? 'Minis 正在执行分工任务'
      : 'Minis 已完成生成，正在收集结果'
    if (run.agents.minis.actionText !== actionText) {
      setCollaborationAgentState(run, 'minis', { actionText })
    }
    if (!running && lastRole === 'assistant') {
      const history = await callMinisChatRpc('chat.messages.list', {
        sessionId,
        limit: 200,
        includeTools: true,
        includeReasoning: true,
      })
      const messages = Array.isArray(history.messages) ? history.messages.map(asRecord).filter(Boolean) : []
      const assistant = [...messages].reverse().find((row) => normalizeText(row?.role) === 'assistant')
      const response = normalizeText(assistant?.content)
      const toolNames = messages.flatMap((row) => {
        const calls = Array.isArray(row?.toolCalls) ? row.toolCalls.map(asRecord).filter(Boolean) : []
        return calls.map((call) => normalizeText(call?.name) || normalizeText(call?.toolName)).filter(Boolean)
      })
      if (toolNames.length > 0) {
        setCollaborationAgentState(run, 'minis', {
          actionText: `Minis 已完成；调用工具：${[...new Set(toolNames)].join('、')}`,
        })
      }
      return response || 'Minis 已完成任务，但未返回可解析的文本回复'
    }
    await sleepMs(900)
  }
  throw new Error('Minis collaboration turn timed out')
}

async function runCollaborationAgent(
  appServer: AppServerProcess,
  run: CollaborationRun,
  agentId: CollaborationAgentId,
  prompt: string,
  phase: 'planning' | 'running' | 'reviewing' = 'running',
  options: {
    assignmentText?: string
    publishResponse?: boolean
    timeoutMs?: number
  } = {},
): Promise<string> {
  const agentName = collaborationAgentLabel(agentId)
  if (isCollaborationRunAborted(run)) throw new Error('Collaboration run aborted')
  setCollaborationAgentState(run, agentId, {
    status: phase,
    startedAtMs: run.agents[agentId].startedAtMs || Date.now(),
    requestText: prompt,
    assignmentText: normalizeText(options.assignmentText),
    actionText: phase === 'planning'
      ? `${agentName}正在判断是否需要委派`
      : phase === 'reviewing'
        ? `${agentName}正在审核成员结果并决定下一步`
        : `${agentName}已收到分工任务`,
    errorText: '',
  })
  try {
    const timeoutMs = options.timeoutMs ?? COLLABORATION_TIMEOUT_MS
    const response = agentId === 'codex'
      ? await runCodexCollaborationTurn(appServer, run, prompt, timeoutMs)
      : agentId === 'claude'
        ? await runClaudeCollaborationTurn(run, prompt, timeoutMs)
        : await runMinisCollaborationTurn(run, prompt, timeoutMs)
    if (isCollaborationRunAborted(run)) throw new Error('Collaboration run aborted')
    setCollaborationAgentState(run, agentId, {
      status: 'completed',
      actionText: phase === 'planning'
        ? `${agentName}已完成任务判断`
        : phase === 'reviewing'
          ? `${agentName}已完成本轮审核`
          : `${agentName}已完成分工任务`,
      ...(options.publishResponse === false ? {} : { responseText: response }),
    })
    return response
  } catch (error) {
    if (isCollaborationRunAborted(run)) {
      if (run.agents[agentId].status !== 'aborted') {
        setCollaborationAgentState(run, agentId, { status: 'aborted' })
      }
      throw error
    }
    const message = getErrorMessage(error, `${agentId} collaboration failed`)
    setCollaborationAgentState(run, agentId, {
      status: 'failed',
      actionText: `${agentName}执行失败`,
      errorText: message,
    })
    throw error
  }
}

function buildCollaborationReviewPrompt(
  run: CollaborationRun,
  userMessage: string,
  coordinatorNotes: string,
  assignments: CollaborationAssignment[],
  additionalUserMessages: string[],
): string {
  const resultSections = assignments.map((assignment) => {
    const state = run.agents[assignment.agentId]
    const content = state.responseText || `执行失败：${state.errorText || state.status}`
    return `【${collaborationAgentLabel(assignment.agentId)}；子任务：${assignment.task}】\n${content}`
  })
  const finalRound = run.roundNumber >= COLLABORATION_MAX_DELEGATION_ROUNDS
  return [
    '[口袋大龙虾三智能体协作：总调度审核]',
    `您是总调度${collaborationAgentLabel(run.leader)}。成员结果已由宿主直接投递，不要等待状态文件或其他消息。`,
    '请核对成员是否真正完成子任务、处理冲突并决定下一步。可以最终回复用户、向用户澄清，或在确有必要时重新委派边界明确的任务。',
    finalRound
      ? '本轮已达到最大委派轮数，禁止继续委派；必须respond给出基于现有证据的最终结论，或ask_user说明缺少的信息。'
      : '不要机械地再次委派；现有证据足够时必须直接respond收口。',
    '请只输出一个JSON对象，不要输出Markdown、解释或代码围栏。格式为：',
    '{"action":"respond或ask_user或delegate","message":"给用户的最终回复、澄清问题或下一轮阶段判断","requiresSharedWorkspace":false,"assignments":[{"agentId":"codex或claude或minis","task":"新的或返工子任务","expectedOutput":"预期结果"}]}',
    'respond和ask_user时assignments必须为空；delegate时不得把任务分配给您自己。',
    '',
    '用户本轮原始消息：',
    userMessage,
    '',
    '您委派前的阶段判断：',
    coordinatorNotes || '未提供',
    ...(additionalUserMessages.length > 0
      ? ['', '用户在执行期间补充的指令，必须优先纳入本次审核：', additionalUserMessages.join('\n')]
      : []),
    '',
    ...resultSections,
  ].join('\n\n')
}

function resetCollaborationTurnAgents(run: CollaborationRun): void {
  for (const agentId of ['codex', 'claude', 'minis'] as const) {
    const previous = run.agents[agentId]
    run.agents[agentId] = {
      ...previous,
      role: agentId === run.leader ? 'leader' : 'worker',
      status: 'idle',
      startedAtMs: 0,
      updatedAtMs: Date.now(),
      requestText: '',
      assignmentText: '',
      actionText: agentId === run.leader ? '等待总调度分析' : '本轮尚未委派',
      responseText: '',
      errorText: '',
    }
  }
  touchCollaborationRun(run)
}

function finishCollaborationTurn(
  run: CollaborationRun,
  decision: CollaborationDecision,
): void {
  const waiting = decision.action === 'ask_user'
  const message = decision.message || (waiting ? '需要您补充信息后才能继续。' : '本轮协作已完成。')
  run.status = waiting ? 'waiting_user' : 'completed'
  run.finalSummary = message
  run.errorText = ''
  run.completedAtMs = Date.now()
  setCollaborationAgentState(run, run.leader, {
    status: waiting ? 'waiting_user' : 'completed',
    actionText: waiting ? `${collaborationAgentLabel(run.leader)}正在等待用户补充` : `${collaborationAgentLabel(run.leader)}已完成最终回复`,
    responseText: message,
  })
  addCollaborationEvent(run, 'final', message, run.leader)
}

async function executeCollaborationRun(
  appServer: AppServerProcess,
  runId: string,
  userMessage: string,
): Promise<void> {
  const run = collaborationRuns.get(runId)
  if (!run) return
  try {
    run.status = 'planning'
    run.completedAtMs = null
    run.errorText = ''
    run.roundNumber = 0
    resetCollaborationTurnAgents(run)
    addCollaborationEvent(run, 'progress', `${collaborationAgentLabel(run.leader)}正在判断是否需要调用协作成员`, run.leader)
    const planningResponse = await runCollaborationAgent(
      appServer,
      run,
      run.leader,
      buildCollaborationCoordinatorPrompt(run, userMessage),
      'planning',
      { publishResponse: false, timeoutMs: COLLABORATION_COORDINATOR_TIMEOUT_MS },
    )
    if (isCollaborationRunAborted(run)) return
    let decision = parseCollaborationDecision(planningResponse, run.leader) ?? {
      action: 'respond' as const,
      message: planningResponse || '总调度未返回可解析的回复。',
      assignments: [],
      requiresSharedWorkspace: false,
    }
    let coordinatorNotes = decision.message
    addCollaborationEvent(
      run,
      'decision',
      decision.action === 'delegate'
        ? `${collaborationAgentLabel(run.leader)}决定调用${decision.assignments.map((row) => collaborationAgentLabel(row.agentId)).join('、')}`
        : decision.action === 'ask_user'
          ? `${collaborationAgentLabel(run.leader)}需要用户补充信息`
          : `${collaborationAgentLabel(run.leader)}判断本轮无需调用其他智能体`,
      run.leader,
    )
    if (decision.action !== 'delegate') {
      finishCollaborationTurn(run, decision)
      await persistCollaborationRunsNow()
      return
    }

    while (decision.action === 'delegate') {
      run.roundNumber += 1
      run.status = 'running'
      if (decision.requiresSharedWorkspace) {
        await prepareCollaborationWorkspace(run)
        addCollaborationEvent(run, 'control', '本轮已按任务需要启用共享文件产物目录', run.leader)
      }
      const selectedIds = new Set(decision.assignments.map((row) => row.agentId))
      for (const agentId of ['codex', 'claude', 'minis'] as const) {
        if (agentId === run.leader) continue
        if (!selectedIds.has(agentId)) {
          setCollaborationAgentState(run, agentId, {
            status: 'idle',
            assignmentText: '',
            actionText: '本轮未被总调度调用',
            responseText: '',
            errorText: '',
          })
        }
      }
      for (const assignment of decision.assignments) {
        addCollaborationEvent(
          run,
          'assignment',
          `${collaborationAgentLabel(assignment.agentId)}负责：${assignment.task}`,
          assignment.agentId,
        )
      }
      setCollaborationAgentState(run, run.leader, {
        status: 'pending',
        actionText: `${collaborationAgentLabel(run.leader)}正在等待成员完成本轮分工`,
      })
      await Promise.allSettled(decision.assignments.map((assignment) =>
        runCollaborationAgent(
          appServer,
          run,
          assignment.agentId,
          buildCollaborationWorkerPrompt(run, assignment, userMessage),
          'running',
          { assignmentText: assignment.task, publishResponse: true },
        ).then((response) => {
          addCollaborationEvent(run, 'result', `${collaborationAgentLabel(assignment.agentId)}已完成分工：${truncateText(response, 1_500)}`, assignment.agentId)
          return response
        }).catch((error) => {
          addCollaborationEvent(run, 'error', `${collaborationAgentLabel(assignment.agentId)}执行失败：${getErrorMessage(error, 'unknown')}`, assignment.agentId)
          throw error
        }),
      ))
      if (isCollaborationRunAborted(run)) return

      run.status = 'reviewing'
      const additionalUserMessages = run.pendingUserMessages.splice(0)
      addCollaborationEvent(run, 'progress', `${collaborationAgentLabel(run.leader)}正在审核成员结果`, run.leader)
      const reviewResponse = await runCollaborationAgent(
        appServer,
        run,
        run.leader,
        buildCollaborationReviewPrompt(run, userMessage, coordinatorNotes, decision.assignments, additionalUserMessages),
        'reviewing',
        { publishResponse: false, timeoutMs: COLLABORATION_COORDINATOR_TIMEOUT_MS },
      )
      if (isCollaborationRunAborted(run)) return
      decision = parseCollaborationDecision(reviewResponse, run.leader) ?? {
        action: 'respond',
        message: reviewResponse || '总调度审核完成，但未返回可解析的最终回复。',
        assignments: [],
        requiresSharedWorkspace: false,
      }
      if (decision.action === 'delegate' && run.roundNumber >= COLLABORATION_MAX_DELEGATION_ROUNDS) {
        decision = {
          action: 'respond',
          message: decision.message || '已达到最大协作轮数，现根据已有结果结束本轮任务。',
          assignments: [],
          requiresSharedWorkspace: false,
        }
      }
      coordinatorNotes = decision.message
      addCollaborationEvent(
        run,
        'decision',
        decision.action === 'delegate'
          ? `${collaborationAgentLabel(run.leader)}审核后决定进入第${run.roundNumber + 1}轮分工`
          : decision.action === 'ask_user'
            ? `${collaborationAgentLabel(run.leader)}审核后需要用户补充信息`
            : `${collaborationAgentLabel(run.leader)}审核完成并准备最终回复`,
        run.leader,
      )
      if (decision.action !== 'delegate') {
        finishCollaborationTurn(run, decision)
        await persistCollaborationRunsNow()
        return
      }
    }
  } catch (error) {
    if (isCollaborationRunAborted(run)) return
    run.status = 'failed'
    run.errorText = getErrorMessage(error, '协作调度失败')
    addCollaborationEvent(run, 'error', run.errorText, run.leader)
  }
  run.completedAtMs = Date.now()
  touchCollaborationRun(run)
  await persistCollaborationRunsNow()
}

async function abortCollaborationRun(appServer: AppServerProcess, run: CollaborationRun): Promise<void> {
  run.status = 'aborted'
  run.completedAtMs = Date.now()
  addCollaborationEvent(run, 'control', '用户已终止本轮协作')
  for (const agentId of ['codex', 'claude', 'minis'] as const) {
    const state = run.agents[agentId]
    if (!['planning', 'running', 'reviewing'].includes(state.status)) continue
    const agentName = collaborationAgentLabel(agentId)
    setCollaborationAgentState(run, agentId, { status: 'aborted', actionText: `${agentName}已终止` })
    try {
      if (agentId === 'codex' && state.sessionId && state.turnId) {
        await appServer.rpc('turn/interrupt', { threadId: state.sessionId, turnId: state.turnId })
      } else if (agentId === 'claude' && state.runId) {
        await abortClaudeRun(state.runId)
      } else if (agentId === 'minis' && state.sessionId) {
        await callMinisChatRpc('chat.session.cancel', { sessionId: state.sessionId })
      }
    } catch {
      // Best effort: state remains visibly aborted even if a child already exited.
    }
  }
  touchCollaborationRun(run)
  await persistCollaborationRunsNow()
}

async function cleanupCollaborationSessions(
  appServer: AppServerProcess,
  run: CollaborationRun,
): Promise<string[]> {
  const warnings: string[] = []
  const codexThreadId = run.agents.codex.sessionId
  if (codexThreadId) {
    try {
      await appServer.rpc('thread/archive', { threadId: codexThreadId })
    } catch (error) {
      warnings.push(`Codex协作会话归档失败：${getErrorMessage(error, 'unknown')}`)
    }
  }

  const claudeSessionId = run.agents.claude.sessionId
  if (claudeSessionId) {
    try {
      const state = await readClaudeState()
      const nextSessions = state.sessions.filter((session) => session.key !== claudeSessionId)
      if (nextSessions.length !== state.sessions.length) {
        state.sessions = nextSessions
        await writeClaudeState(state)
      }
      for (const [runId, child] of claudeRuns.entries()) {
        if (child.sessionKey === claudeSessionId) claudeRuns.delete(runId)
      }
      const persistedRuns = await readClaudeRunsState()
      await writeClaudeRunsState(persistedRuns.filter((child) => child.sessionKey !== claudeSessionId))
      if (homeDir) {
        const mirrorId = claudeSessionId.replace(/[^a-zA-Z0-9._-]/gu, '_')
        await unlink(join(homeDir, '.pocketlobster', 'agent-sessions', 'claude-code', `${mirrorId}.json`)).catch(() => undefined)
      }
    } catch (error) {
      warnings.push(`Claude Code协作会话清理失败：${getErrorMessage(error, 'unknown')}`)
    }
  }

  const minisSessionId = run.agents.minis.sessionId
  if (minisSessionId) {
    try {
      await callMinisChatRpc('chat.session.delete', { sessionId: minisSessionId })
    } catch (error) {
      warnings.push(`Minis协作会话清理失败：${getErrorMessage(error, 'unknown')}`)
    }
  }
  return warnings
}

function publicCollaborationText(value: string, limit = 80_000): string {
  return truncateText(normalizeText(value).replace(/collab_[a-z0-9_]+/giu, '本轮协作'), limit)
}

function collaborationRunSummary(run: CollaborationRun): Record<string, unknown> {
  const agents = Object.fromEntries((['codex', 'claude', 'minis'] as const).map((agentId) => {
    const state = run.agents[agentId]
    return [agentId, {
      agentId,
      role: state.role,
      status: state.status,
      startedAtMs: state.startedAtMs,
      updatedAtMs: state.updatedAtMs,
      assignmentText: publicCollaborationText(state.assignmentText, 1_200),
      actionText: publicCollaborationText(state.actionText, 800),
      responseText: publicCollaborationText(state.responseText),
      errorText: publicCollaborationText(state.errorText, 1_200),
    }]
  }))
  return {
    id: run.id,
    title: run.title,
    prompt: run.prompt,
    leader: run.leader,
    status: run.status,
    createdAtMs: run.createdAtMs,
    updatedAtMs: run.updatedAtMs,
    completedAtMs: run.completedAtMs,
    turnNumber: run.turnNumber,
    roundNumber: run.roundNumber,
    archived: run.archived,
    workspaceReady: run.workspaceReady,
    events: run.events.map((event) => ({
      ...event,
      text: publicCollaborationText(event.text, 2_000),
    })),
    agents,
    finalSummary: publicCollaborationText(run.finalSummary),
    errorText: publicCollaborationText(run.errorText, 1_200),
  }
}

function collaborationRunExport(run: CollaborationRun): Record<string, unknown> {
  const summary = collaborationRunSummary(run)
  const agents = Object.fromEntries((['codex', 'claude', 'minis'] as const).map((agentId) => {
    const state = run.agents[agentId]
    return [agentId, {
      agentId,
      role: state.role,
      status: state.status,
      startedAtMs: state.startedAtMs,
      updatedAtMs: state.updatedAtMs,
      assignmentText: publicCollaborationText(state.assignmentText),
      actionText: publicCollaborationText(state.actionText),
      responseText: publicCollaborationText(state.responseText),
      errorText: publicCollaborationText(state.errorText),
    }]
  }))
  return {
    ...summary,
    prompt: publicCollaborationText(run.prompt),
    events: run.events.map((event) => ({
      ...event,
      text: publicCollaborationText(event.text),
    })),
    agents,
  }
}

export function createCodexBridgeMiddleware(): CodexBridgeMiddleware {
  const { appServer, methodCatalog } = getSharedBridgeState()

  const middleware = async (req: IncomingMessage, res: ServerResponse, next: () => void) => {
    try {
      if (!req.url) {
        next()
        return
      }

      const url = new URL(req.url, 'http://localhost')

      if (req.method === 'GET' && url.pathname === '/host-api/health') {
        setJson(res, 200, {
          ok: true,
          bundleId: SERVER_BUNDLE_ID,
        })
        return
      }

      if (req.method === 'GET' && url.pathname === '/collaboration-api/runs') {
        await ensureCollaborationRunsLoaded()
        const runs = [...collaborationRuns.values()]
          .sort((a, b) => b.createdAtMs - a.createdAtMs)
          .slice(0, COLLABORATION_HISTORY_LIMIT)
          .map(collaborationRunSummary)
        setJson(res, 200, { ok: true, runs })
        return
      }

      if (req.method === 'GET' && url.pathname === '/collaboration-api/status') {
        await ensureCollaborationRunsLoaded()
        const runId = normalizeText(url.searchParams.get('runId'))
        const run = collaborationRuns.get(runId)
        if (!run) {
          setJson(res, 404, { ok: false, error: 'Collaboration run not found' })
          return
        }
        setJson(res, 200, { ok: true, run: collaborationRunSummary(run) })
        return
      }

      if (req.method === 'GET' && url.pathname === '/collaboration-api/export') {
        await ensureCollaborationRunsLoaded()
        const runId = normalizeText(url.searchParams.get('runId'))
        const run = collaborationRuns.get(runId)
        if (!run) {
          setJson(res, 404, { ok: false, error: 'Collaboration run not found' })
          return
        }
        setJson(res, 200, { ok: true, run: collaborationRunExport(run) })
        return
      }

      if (req.method === 'POST' && url.pathname === '/collaboration-api/start') {
        await ensureCollaborationRunsLoaded()
        const payload = asRecord(await readJsonBody(req))
        const prompt = normalizeText(payload?.prompt)
        if (!prompt) {
          setJson(res, 400, { ok: false, error: 'Missing collaboration prompt' })
          return
        }
        const leader = normalizeCollaborationAgent(payload?.leader)
        const activeRun = [...collaborationRuns.values()].find(isCollaborationRunActive)
        if (activeRun) {
          setJson(res, 409, {
            ok: false,
            error: `已有三智能体协作任务正在运行：${activeRun.title}，请等待完成或先终止`,
            run: collaborationRunSummary(activeRun),
          })
          return
        }
        const now = Date.now()
        const id = `collab_${now.toString(36)}_${randomUUID().slice(0, 8)}`
        const titleSeed = prompt.replace(/\s+/gu, ' ').trim().slice(0, 42)
        const run: CollaborationRun = {
          id,
          title: titleSeed ? `三智能体协作：${titleSeed}` : `三智能体协作 ${id.slice(-6)}`,
          prompt,
          leader,
          status: 'planning',
          createdAtMs: now,
          updatedAtMs: now,
          completedAtMs: null,
          turnNumber: 1,
          roundNumber: 0,
          archived: false,
          workspaceReady: false,
          pendingUserMessages: [],
          events: [],
          agents: collaborationAgentRecord(leader),
          finalSummary: '',
          errorText: '',
        }
        collaborationRuns.set(id, run)
        addCollaborationEvent(run, 'user', prompt)
        await persistCollaborationRunsNow()
        void executeCollaborationRun(appServer, id, prompt)
        setJson(res, 202, { ok: true, run: collaborationRunSummary(run) })
        return
      }

      if (req.method === 'POST' && url.pathname === '/collaboration-api/continue') {
        await ensureCollaborationRunsLoaded()
        const payload = asRecord(await readJsonBody(req))
        const runId = normalizeText(payload?.runId)
        const prompt = normalizeText(payload?.prompt)
        const run = collaborationRuns.get(runId)
        if (!run) {
          setJson(res, 404, { ok: false, error: 'Collaboration run not found' })
          return
        }
        if (!prompt) {
          setJson(res, 400, { ok: false, error: 'Missing collaboration prompt' })
          return
        }
        if (run.archived) {
          setJson(res, 409, { ok: false, error: '已归档的协作会话不能继续，请先取消归档' })
          return
        }
        if (isCollaborationRunActive(run)) {
          addCollaborationEvent(run, 'user', prompt)
          run.pendingUserMessages.push(prompt)
          addCollaborationEvent(run, 'control', '补充指令已提交，将在总调度下一次审核时处理')
          await persistCollaborationRunsNow()
          setJson(res, 202, { ok: true, queued: true, run: collaborationRunSummary(run) })
          return
        }
        const otherActive = [...collaborationRuns.values()].find((candidate) => candidate.id !== run.id && isCollaborationRunActive(candidate))
        if (otherActive) {
          setJson(res, 409, { ok: false, error: `已有三智能体协作任务正在运行：${otherActive.title}` })
          return
        }
        addCollaborationEvent(run, 'user', prompt)
        run.turnNumber += 1
        run.status = 'planning'
        run.completedAtMs = null
        run.finalSummary = ''
        run.errorText = ''
        run.pendingUserMessages = []
        await persistCollaborationRunsNow()
        void executeCollaborationRun(appServer, run.id, prompt)
        setJson(res, 202, { ok: true, queued: false, run: collaborationRunSummary(run) })
        return
      }

      if (req.method === 'POST' && url.pathname === '/collaboration-api/abort') {
        await ensureCollaborationRunsLoaded()
        const payload = asRecord(await readJsonBody(req))
        const runId = normalizeText(payload?.runId)
        const run = collaborationRuns.get(runId)
        if (!run) {
          setJson(res, 404, { ok: false, error: 'Collaboration run not found' })
          return
        }
        if (!isCollaborationRunActive(run)) {
          setJson(res, 409, { ok: false, error: '该协作任务已经结束，无需再次终止', run: collaborationRunSummary(run) })
          return
        }
        await abortCollaborationRun(appServer, run)
        setJson(res, 200, { ok: true, run: collaborationRunSummary(run) })
        return
      }

      if (req.method === 'POST' && url.pathname === '/collaboration-api/rename') {
        await ensureCollaborationRunsLoaded()
        const payload = asRecord(await readJsonBody(req))
        const run = collaborationRuns.get(normalizeText(payload?.runId))
        const title = normalizeText(payload?.title).slice(0, 100)
        if (!run) {
          setJson(res, 404, { ok: false, error: 'Collaboration run not found' })
          return
        }
        if (!title) {
          setJson(res, 400, { ok: false, error: '任务名称不能为空' })
          return
        }
        run.title = title
        touchCollaborationRun(run)
        await persistCollaborationRunsNow()
        setJson(res, 200, { ok: true, run: collaborationRunSummary(run) })
        return
      }

      if (req.method === 'POST' && url.pathname === '/collaboration-api/archive') {
        await ensureCollaborationRunsLoaded()
        const payload = asRecord(await readJsonBody(req))
        const run = collaborationRuns.get(normalizeText(payload?.runId))
        if (!run) {
          setJson(res, 404, { ok: false, error: 'Collaboration run not found' })
          return
        }
        if (isCollaborationRunActive(run)) {
          setJson(res, 409, { ok: false, error: '运行中的协作任务不能归档' })
          return
        }
        run.archived = payload?.archived !== false
        touchCollaborationRun(run)
        await persistCollaborationRunsNow()
        setJson(res, 200, { ok: true, run: collaborationRunSummary(run) })
        return
      }

      if (req.method === 'POST' && url.pathname === '/collaboration-api/delete') {
        await ensureCollaborationRunsLoaded()
        const payload = asRecord(await readJsonBody(req))
        const runId = normalizeText(payload?.runId)
        const run = collaborationRuns.get(runId)
        if (!run) {
          setJson(res, 404, { ok: false, error: 'Collaboration run not found' })
          return
        }
        if (isCollaborationRunActive(run)) {
          setJson(res, 409, { ok: false, error: '请先终止运行中的协作任务再删除' })
          return
        }
        const cleanupWarnings = await cleanupCollaborationSessions(appServer, run)
        collaborationRuns.delete(runId)
        await persistCollaborationRunsNow()
        setJson(res, 200, { ok: true, runId, cleanupWarnings })
        return
      }

      if (await handleCodexProviderAdapterRequest(req, res, url, {
        catalogPath: codexModelProvidersPath,
        runtimeStatusPath: codexProviderRuntimeStatusPath,
        diagnosticPath: codexChatDiagnosticPath,
      })) {
        return
      }

      if (req.method === 'POST' && url.pathname === '/codex-api/rpc') {
        if (!appServer.isCommandAvailable()) {
          setJson(res, 503, { error: appServer.getUnavailableReason() || 'Codex CLI not installed' })
          return
        }
        const payload = await readJsonBody(req)
        const body = asRecord(payload) as RpcProxyRequest | null

        if (!body || typeof body.method !== 'string' || body.method.length === 0) {
          setJson(res, 400, { error: 'Invalid body: expected { method, params? }' })
          return
        }

        const trackedRpc = body.method === 'thread/start' || body.method === 'thread/resume' || body.method === 'thread/read' || body.method === 'turn/start'
        const requiresProviderDefinitions = body.method === 'thread/start' || body.method === 'thread/resume' || body.method === 'turn/start'
        if (trackedRpc) await appendCodexDiagnostic('rpc_request', diagnosticRpcFields(body.method, body.params))
        try {
          if (requiresProviderDefinitions) await ensureCodexProviderDefinitions(appServer)

          let nextParams: unknown = body.params ?? null
          if (shouldInjectDeveloperInstructions(body.method)) {
            const paramsRecord = asRecord(nextParams) ?? {}
            const injected = await buildInjectedDeveloperInstructions(paramsRecord)
            if (injected.length > 0) {
              paramsRecord.developerInstructions = mergeDeveloperInstructions(
                paramsRecord.developerInstructions,
                injected,
              )
              nextParams = paramsRecord
            }
          }

          const result = await appServer.rpc(body.method, nextParams)
          if (trackedRpc) await appendCodexDiagnostic('rpc_success', diagnosticRpcFields(body.method, body.params, result))
          setJson(res, 200, { result })
        } catch (error) {
          if (trackedRpc) {
            await appendCodexDiagnostic('rpc_failure', {
              ...diagnosticRpcFields(body.method, body.params),
              error: getErrorMessage(error, 'Unknown Codex RPC error').slice(0, 800),
            })
          }
          throw error
        }
        return
      }

      if (req.method === 'GET' && url.pathname === '/codex-api/availability') {
        setJson(res, 200, {
          ok: appServer.isCommandAvailable(),
          reason: appServer.getUnavailableReason(),
        })
        return
      }

      if (req.method === 'GET' && url.pathname === '/codex-api/model-providers') {
        if (!codexModelProvidersPath) {
          setJson(res, 200, { version: 1, currentConfigId: '', configs: [] })
          return
        }
        try {
          await ensureCodexProviderDefinitions(appServer)
          await refreshStoredProviderModels()
          const parsed = JSON.parse(await readFile(codexModelProvidersPath, 'utf8')) as unknown
          const record = asRecord(parsed)
          const configs = Array.isArray(record?.configs) ? record.configs : []
          setJson(res, 200, {
            version: 1,
            currentConfigId: normalizeText(record?.currentConfigId),
            configs,
          })
        } catch {
          setJson(res, 200, { version: 1, currentConfigId: '', configs: [] })
        }
        return
      }

      if (req.method === 'POST' && url.pathname === '/codex-api/model-selection') {
        const payload = asRecord(await readJsonBody(req))
        const providerId = normalizeText(payload?.providerId) || 'openai'
        const model = normalizeText(payload?.model)
        const result = await selectCodexModel(appServer, providerId, model)
        setJson(res, 200, result)
        return
      }

      if (req.method === 'POST' && url.pathname === '/codex-api/thread-route') {
        const payload = asRecord(await readJsonBody(req))
        const threadId = normalizeText(payload?.threadId)
        const providerId = normalizeText(payload?.providerId) || 'openai'
        const model = normalizeText(payload?.model)
        const result = await switchCodexThreadRoute(appServer, threadId, providerId, model)
        setJson(res, 200, result)
        return
      }

      if (req.method === 'GET' && url.pathname === '/codex-api/thread-route') {
        const threadId = normalizeText(url.searchParams.get('threadId'))
        if (!threadId) {
          setJson(res, 400, { error: 'Missing threadId' })
          return
        }
        const read = asRecord(await appServer.rpc('thread/read', { threadId, includeTurns: false }))
        const providerId = normalizeText(asRecord(read?.thread)?.modelProvider) || 'openai'
        const model = await readPersistedThreadModel(threadId)
        setJson(res, 200, { providerId, model })
        return
      }

      if (req.method === 'GET' && url.pathname === '/codex-api/model-provider-status') {
        const catalog = await readJsonFile(codexModelProvidersPath)
        const runtime = await readJsonFile(codexProviderRuntimeStatusPath)
        let codexConfig: Record<string, unknown> = {}
        let configError = ''
        try {
          const read = asRecord(await appServer.rpc('config/read', {}))
          codexConfig = asRecord(read?.config) ?? {}
        } catch (error) {
          configError = getErrorMessage(error, 'Codex config/read failed')
        }
        const selectedProviderId = normalizeText(codexConfig.model_provider) || 'openai'
        const selectedModel = normalizeText(codexConfig.model)
        const configs = Array.isArray(catalog?.configs) ? catalog.configs : []
        const selected = configs
          .map(asRecord)
          .find((row) => normalizeText(row?.providerId) === selectedProviderId)
        const configuredModel = normalizeText(selected?.modelId)
        const runtimeProviders = asRecord(runtime?.providers)
        const runtimeStatus = selected ? asRecord(runtimeProviders?.[normalizeText(selected.id)]) : null
        setJson(res, 200, {
          ok: !configError,
          providerId: selectedProviderId,
          providerName: normalizeText(selected?.displayName) || selectedProviderId,
          model: selectedModel,
          configuredModel,
          upstreamProtocol: normalizeText(selected?.upstreamProtocol) || (selectedProviderId === 'openai' ? 'responses' : ''),
          routeMatches: selectedProviderId === 'openai' || (!!selected && selectedModel === configuredModel),
          verificationStatus: normalizeText(selected?.verificationStatus),
          lastVerifiedAt: normalizeText(selected?.lastVerifiedAt),
          verifiedModel: normalizeText(selected?.verifiedModel),
          runtime: runtimeStatus,
          error: configError,
        })
        return
      }

      if (req.method === 'POST' && url.pathname === '/codex-api/model-providers/reload') {
        await readJsonBody(req)
        const loaded = reloadCodexProviderSecretEnvironment()
        appServer.dispose()
        setJson(res, 200, { ok: true, loaded })
        return
      }

      if (req.method === 'POST' && url.pathname === '/codex-api/model-providers/end-to-end-test') {
        const payload = asRecord(await readJsonBody(req))
        const providerId = normalizeText(payload?.providerId)
        const model = normalizeText(payload?.model)
        if (!providerId || !model) {
          setJson(res, 400, { error: 'Missing providerId or model' })
          return
        }
        const catalog = await readJsonFile(codexModelProvidersPath)
        const configs = Array.isArray(catalog?.configs) ? catalog.configs : []
        const selected = configs
          .map(asRecord)
          .find((row) => normalizeText(row?.providerId) === providerId)
        if (!selected) {
          setJson(res, 404, { error: 'Provider configuration not found' })
          return
        }
        const startedAtMs = Date.now()
        const result = await verifyCodexProviderEndToEnd(appServer, providerId, model)
        const runtimeStatus = await waitForProviderRuntimeStatus(
          normalizeText(selected.id),
          providerId,
          model,
          startedAtMs,
        )
        setJson(res, 200, {
          ...result,
          runtime: runtimeStatus,
        })
        return
      }

      if (req.method === 'GET' && url.pathname === '/openclaw-api/health') {
        if (isOpenClawLightweightOnlyMode()) {
          setJson(res, 200, {
            ok: true,
            data: {
              mode: 'lightweight-proxy',
              backendMode: OPENCLAW_BACKEND_MODE,
              gatewayRequired: false,
            },
          })
          return
        }
        const nativeHealth = await tryRunOpenClawGatewayCall('health', {})
        if (nativeHealth !== null) {
          const record = asRecord(nativeHealth) ?? {}
          setJson(res, 200, {
            ok: true,
            data: {
              mode: 'native-gateway',
              gatewayRequired: true,
              ...record,
            },
          })
          return
        }

        if (OPENCLAW_NATIVE_STRICT_MODE) {
          setJson(res, 200, {
            ok: false,
            error: 'OpenClaw gateway unavailable',
            code: 'openclaw_native_unavailable',
            data: {
              mode: 'lightweight-proxy',
              gatewayRequired: true,
            },
          })
          return
        }

        setJson(res, 200, {
          ok: true,
          data: {
            mode: 'lightweight-proxy',
            gatewayRequired: false,
          },
        })
        return
      }

      if (req.method === 'GET' && url.pathname === '/openclaw-api/sessions') {
        const limit = readPositiveInt(url.searchParams.get('limit'), 200)
        if (isOpenClawLightweightOnlyMode()) {
          const sessions = await listLightweightSessions(limit)
          setJson(res, 200, { sessions })
          return
        }
        if (await isNativeOpenClawReady()) {
          try {
            const nativeSessions = await runOpenClawGatewayCall(
              'sessions.list',
              {
                limit,
                includeDerivedTitles: true,
                includeLastMessage: true,
                includeGlobal: true,
                includeUnknown: true,
              },
              OPENCLAW_HISTORY_CALL_TIMEOUT_MS,
            )
            const record = asRecord(nativeSessions)
            const sessions = Array.isArray(record?.sessions)
              ? record.sessions
                .map((row) => toOpenClawSessionSummary(asRecord(row) ?? {}))
                .filter((row): row is Record<string, unknown> => row !== null)
              : []
            setJson(res, 200, { sessions })
            return
          } catch {
            invalidateOpenClawNativeReadyCache()
            if (OPENCLAW_NATIVE_STRICT_MODE) {
              setOpenClawNativeUnavailable(
                res,
                502,
                'OpenClaw sessions temporarily unavailable',
                'openclaw_native_sessions_unavailable',
              )
              return
            }
          }
        }
        if (OPENCLAW_NATIVE_STRICT_MODE) {
          setOpenClawNativeUnavailable(
            res,
            503,
            'OpenClaw gateway unavailable',
            'openclaw_native_unavailable',
          )
          return
        }

        const sessions = await listLightweightSessions(limit)
        setJson(res, 200, { sessions })
        return
      }

      if (req.method === 'POST' && url.pathname === '/openclaw-api/history') {
        const payload = asRecord(await readJsonBody(req))
        const sessionKey = normalizeText(payload?.sessionKey)
        if (!sessionKey) {
          setJson(res, 400, { error: 'Missing sessionKey' })
          return
        }
        const limit = readPositiveInt(String(payload?.limit ?? ''), 60)
        if (isOpenClawLightweightOnlyMode()) {
          const result = await readLightweightHistory(sessionKey, limit)
          setJson(res, 200, result)
          return
        }
        if (await isNativeOpenClawReady()) {
          try {
            const nativeHistory = await runOpenClawGatewayCall(
              'chat.history',
              {
                sessionKey,
                limit,
              },
              OPENCLAW_HISTORY_CALL_TIMEOUT_MS,
            )
            const record = asRecord(nativeHistory) ?? {}
            setJson(res, 200, {
              sessionKey: normalizeText(record.sessionKey) || sessionKey,
              messages: Array.isArray(record.messages) ? record.messages : [],
              thinkingLevel: normalizeText(record.thinkingLevel) || 'medium',
            })
            return
          } catch {
            invalidateOpenClawNativeReadyCache()
            if (OPENCLAW_NATIVE_STRICT_MODE) {
              setOpenClawNativeUnavailable(
                res,
                502,
                'OpenClaw history temporarily unavailable',
                'openclaw_native_history_unavailable',
              )
              return
            }
          }
        }
        if (OPENCLAW_NATIVE_STRICT_MODE) {
          setOpenClawNativeUnavailable(
            res,
            503,
            'OpenClaw gateway unavailable',
            'openclaw_native_unavailable',
          )
          return
        }

        const result = await readLightweightHistory(sessionKey, limit)
        setJson(res, 200, result)
        return
      }

      if (req.method === 'POST' && url.pathname === '/openclaw-api/send') {
        const payload = asRecord(await readJsonBody(req))
        const sessionKey = normalizeText(payload?.sessionKey)
        const message = normalizeText(payload?.message)
        const attachments = normalizeOpenClawAttachments(payload?.attachments)
        if (!sessionKey || (!message && attachments.length === 0)) {
          setJson(res, 400, { error: 'Missing sessionKey and message/attachments' })
          return
        }

        const sendTimeoutMs = normalizeTimeoutMs(
          payload?.timeoutMs,
          OPENCLAW_CHAT_SEND_TIMEOUT_MS,
          8_000,
          240_000,
        )

        if (isOpenClawLightweightOnlyMode()) {
          const run = await sendLightweightMessage({
            sessionKey,
            message,
            deliver: payload?.deliver === true,
            attachments: attachments.length > 0 ? attachments : undefined,
            timeoutMs: sendTimeoutMs,
          })
          setJson(res, 200, { ok: true, runId: run.runId })
          return
        }

        if (await isNativeOpenClawReady()) {
          try {
            const nativeRun = await runOpenClawGatewayCall(
              'chat.send',
              {
                sessionKey,
                message,
                deliver: payload?.deliver === true,
                attachments: attachments.length > 0 ? attachments : undefined,
                timeoutMs: sendTimeoutMs,
                idempotencyKey: `mobile-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
              },
              sendTimeoutMs + 20_000,
            )
            const record = asRecord(nativeRun)
            const runId = normalizeText(record?.runId)
            if (!runId) {
              setJson(res, 502, { error: 'chat.send missing runId' })
              return
            }
            rememberOpenClawNativeReady(true)
            rememberOpenClawNativeRun(runId, sessionKey)
            setJson(res, 200, { ok: true, runId })
            return
          } catch {
            invalidateOpenClawNativeReadyCache()
            if (OPENCLAW_NATIVE_STRICT_MODE) {
              setOpenClawNativeUnavailable(
                res,
                502,
                'OpenClaw send temporarily unavailable',
                'openclaw_native_send_unavailable',
              )
              return
            }
          }
        }
        if (OPENCLAW_NATIVE_STRICT_MODE) {
          setOpenClawNativeUnavailable(
            res,
            503,
            'OpenClaw gateway unavailable',
            'openclaw_native_unavailable',
          )
          return
        }

        const run = await sendLightweightMessage({
          sessionKey,
          message,
          deliver: payload?.deliver === true,
          attachments: attachments.length > 0 ? attachments : undefined,
        })
        setJson(res, 200, { ok: true, runId: run.runId })
        return
      }

      if (req.method === 'POST' && url.pathname === '/openclaw-api/run/wait') {
        const payload = asRecord(await readJsonBody(req))
        const runId = normalizeText(payload?.runId)
        if (!runId) {
          setJson(res, 400, { error: 'Missing runId' })
          return
        }

        const waitTimeoutMs = normalizeTimeoutMs(
          payload?.timeoutMs,
          OPENCLAW_RUN_WAIT_TIMEOUT_MS,
          2_000,
          120_000,
        )

        if (isOpenClawLightweightOnlyMode()) {
          const startedAt = Date.now()
          while (Date.now() - startedAt < waitTimeoutMs) {
            const status = toLightweightRunWaitPayload(runId)
            if (status.completed === true) {
              setJson(res, 200, status)
              return
            }
            await sleepMs(220)
          }
          setJson(res, 200, toLightweightRunWaitPayload(runId))
          return
        }

        if (await isNativeOpenClawReady()) {
          const waitAttempts = OPENCLAW_RUN_WAIT_ATTEMPTS
          for (let attempt = 0; attempt < waitAttempts; attempt += 1) {
            try {
              const gatewayWaitTimeoutMs = Math.min(
                waitTimeoutMs + OPENCLAW_RUN_WAIT_GATEWAY_GRACE_MS,
                45_000,
              )
              const nativeWait = await runOpenClawGatewayCall(
                'agent.wait',
                {
                  runId,
                  timeoutMs: waitTimeoutMs,
                },
                gatewayWaitTimeoutMs,
              )
              const record = asRecord(nativeWait) ?? {}
              const rawStatus = normalizeText(record.status).toLowerCase()
              const status = rawStatus === 'timeout' ? 'running' : (rawStatus || 'running')
              const completed = OPENCLAW_COMPLETED_RUN_STATUSES.has(status)
              rememberOpenClawNativeReady(true)
              updateOpenClawNativeRun(runId, status, '')
              if (completed) {
                clearOpenClawNativeRun(runId)
              }
              setJson(res, 200, {
                ok: true,
                runId,
                status,
                completed,
                result: record.result ?? null,
                error: record.error ?? null,
                rawStatus,
              })
              return
            } catch (error) {
              invalidateOpenClawNativeReadyCache()
              const errorMessage = getErrorMessage(error, 'agent.wait failed')
              const retryable = isOpenClawGatewayRetryableError(errorMessage)
              updateOpenClawNativeRun(runId, 'reconnecting', errorMessage)
              if (retryable && attempt < waitAttempts - 1) {
                await sleepMs(350 + attempt * 500)
                continue
              }
              const probe = await probeOpenClawRunCompletionByHistory(runId)
              if (probe) {
                if (probe.completed) {
                  clearOpenClawNativeRun(runId)
                } else {
                  updateOpenClawNativeRun(runId, probe.status, '')
                }
                setJson(res, 200, {
                  ok: probe.completed,
                  runId,
                  status: probe.status,
                  completed: probe.completed,
                  result: probe.result ?? null,
                  error: probe.error ?? null,
                  source: 'history-probe',
                })
                return
              }
              setJson(res, 200, {
                ok: false,
                runId,
                status: retryable ? 'reconnecting' : 'failed',
                completed: false,
                error: errorMessage,
                retryable,
              })
              return
            }
          }
        }
        if (OPENCLAW_NATIVE_STRICT_MODE) {
          setJson(res, 503, {
            ok: false,
            runId,
            status: 'reconnecting',
            completed: false,
            error: 'OpenClaw gateway unavailable',
            code: 'openclaw_native_unavailable',
            retryable: true,
          })
          return
        }

        setJson(res, 200, {
          ok: true,
          runId,
          status: 'completed',
          completed: true,
          source: 'lightweight',
        })
        return
      }

      if (req.method === 'POST' && url.pathname === '/openclaw-api/heartbeat/trigger') {
        const payload = asRecord(await readJsonBody(req))
        const sessionKey = normalizeText(payload?.sessionKey)

        if (isOpenClawLightweightOnlyMode()) {
          if (!sessionKey) {
            setJson(res, 400, { ok: false, error: 'Missing sessionKey for heartbeat' })
            return
          }
          const run = await sendLightweightMessage({
            sessionKey,
            message: OPENCLAW_HEARTBEAT_PROMPT,
            deliver: false,
          })
          setJson(res, 200, {
            ok: true,
            status: 'submitted',
            runId: run.runId,
            source: 'lightweight',
            message: 'Heartbeat lightweight task submitted',
          })
          return
        }

        if (OPENCLAW_NATIVE_STRICT_MODE && !(await isNativeOpenClawReady())) {
          setOpenClawNativeUnavailable(
            res,
            503,
            'OpenClaw gateway unavailable',
            'openclaw_native_unavailable',
          )
          return
        }

        try {
          if (await isNativeOpenClawReady()) {
            try {
              const cronList = await runOpenClawGatewayCall(
                'cron.list',
                {
                  includeDisabled: false,
                  limit: 200,
                  offset: 0,
                },
                20_000,
              )
              const heartbeatJob = findHeartbeatCronJob(cronList)
              if (heartbeatJob) {
                const cronRun = await runOpenClawGatewayCall(
                  'cron.run',
                  {
                    id: heartbeatJob.id,
                    mode: 'force',
                  },
                  30_000,
                )
                const cronRunRecord = asRecord(cronRun) ?? {}
                const runId = normalizeText(cronRunRecord.runId)
                if (runId && sessionKey) {
                  rememberOpenClawNativeRun(runId, sessionKey)
                }
                setJson(res, 200, {
                  ok: true,
                  status: 'submitted',
                  runId,
                  source: 'cron.run',
                  message: `Triggered ${heartbeatJob.name}`,
                })
                return
              }
            } catch {
              invalidateOpenClawNativeReadyCache()
            }

            if (!sessionKey) {
              setJson(res, 400, {
                ok: false,
                error: 'Missing sessionKey for heartbeat fallback',
              })
              return
            }

            const fallbackRun = await runOpenClawGatewayCall(
              'chat.send',
              {
                sessionKey,
                message: OPENCLAW_HEARTBEAT_PROMPT,
                deliver: false,
                timeoutMs: OPENCLAW_CHAT_SEND_TIMEOUT_MS,
                idempotencyKey: `heartbeat-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
              },
              OPENCLAW_CHAT_SEND_TIMEOUT_MS + 20_000,
            )
            const record = asRecord(fallbackRun) ?? {}
            const runId = normalizeText(record.runId)
            if (runId) {
              rememberOpenClawNativeRun(runId, sessionKey)
            }
            setJson(res, 200, {
              ok: true,
              status: runId ? 'submitted' : 'running',
              runId,
              source: 'chat.send',
              message: 'Heartbeat fallback message submitted',
            })
            return
          }
        } catch {
          invalidateOpenClawNativeReadyCache()
          setOpenClawNativeUnavailable(
            res,
            502,
            'OpenClaw heartbeat trigger failed',
            'openclaw_native_heartbeat_unavailable',
          )
          return
        }

        if (OPENCLAW_NATIVE_STRICT_MODE) {
          setOpenClawNativeUnavailable(
            res,
            503,
            'OpenClaw gateway unavailable',
            'openclaw_native_unavailable',
          )
          return
        }

        setJson(res, 503, {
          ok: false,
          error: 'OpenClaw heartbeat unavailable',
        })
        return
      }

      if (req.method === 'POST' && url.pathname === '/openclaw-api/run/abort') {
        const payload = asRecord(await readJsonBody(req))
        const sessionKey = normalizeText(payload?.sessionKey)
        const runId = normalizeText(payload?.runId)
        if (!sessionKey && !runId) {
          setJson(res, 400, { error: 'Missing sessionKey or runId' })
          return
        }

        if (isOpenClawLightweightOnlyMode()) {
          const targetRunId = runId || findLatestRunningLightweightRun(sessionKey)
          if (!targetRunId) {
            setJson(res, 200, {
              ok: false,
              aborted: false,
              status: 'not_found',
              source: 'lightweight',
            })
            return
          }
          lightweightRunAbortRequested.add(targetRunId)
          updateLightweightRun(targetRunId, 'aborted', true, '任务已中止')
          setJson(res, 200, {
            ok: true,
            aborted: true,
            status: 'aborted',
            runId: targetRunId,
            source: 'lightweight',
          })
          return
        }

        if (OPENCLAW_NATIVE_STRICT_MODE && !(await isNativeOpenClawReady())) {
          setOpenClawNativeUnavailable(
            res,
            503,
            'OpenClaw gateway unavailable',
            'openclaw_native_unavailable',
          )
          return
        }

        try {
          if (await isNativeOpenClawReady()) {
            const params: Record<string, unknown> = {}
            if (sessionKey) params.sessionKey = sessionKey
            if (runId) params.runId = runId
            await runOpenClawGatewayCall(
              'chat.abort',
              params,
              20_000,
            )
            if (runId) {
              clearOpenClawNativeRun(runId)
            }
            setJson(res, 200, {
              ok: true,
              aborted: true,
              status: 'aborted',
              source: 'chat.abort',
            })
            return
          }
        } catch {
          invalidateOpenClawNativeReadyCache()
          setOpenClawNativeUnavailable(
            res,
            502,
            'OpenClaw abort failed',
            'openclaw_native_abort_unavailable',
          )
          return
        }

        if (OPENCLAW_NATIVE_STRICT_MODE) {
          setOpenClawNativeUnavailable(
            res,
            503,
            'OpenClaw gateway unavailable',
            'openclaw_native_unavailable',
          )
          return
        }

        setJson(res, 503, {
          ok: false,
          aborted: false,
          status: 'failed',
          source: 'unavailable',
        })
        return
      }


      if (req.method === 'POST' && url.pathname === '/openclaw-api/attachments/upload-stream') {
        const fileName = sanitizeOpenClawUploadFileName(
          normalizeText(url.searchParams.get('fileName')) ||
          readHeaderText(req.headers['x-file-name']) ||
          'attachment.bin',
        )
        const contentType = readHeaderText(req.headers['content-type'])
        const mimeType =
          normalizeText(url.searchParams.get('mimeType')) ||
          readHeaderText(req.headers['x-mime-type']) ||
          (contentType.split(';')[0]?.trim() || 'application/octet-stream')

        await mkdir(OPENCLAW_UPLOAD_DIR, { recursive: true })
        const storedName = buildOpenClawUploadStoredName(fileName)
        const storedPath = join(OPENCLAW_UPLOAD_DIR, storedName)

        try {
          const sizeBytes = await writeOpenClawUploadStream(
            req,
            storedPath,
            OPENCLAW_UPLOAD_STREAM_MAX_BYTES,
          )
          setJson(res, 200, {
            ok: true,
            path: storedPath,
            fileName,
            mimeType,
            sizeBytes,
          })
        } catch (error) {
          await unlink(storedPath).catch(() => undefined)
          const message = error instanceof Error ? error.message : 'Attachment upload failed'
          if (message.includes('Attachment exceeds size limit')) {
            setJson(res, 400, { error: message })
          } else {
            setJson(res, 500, { error: message })
          }
        }
        return
      }

      if (req.method === 'POST' && url.pathname === '/openclaw-api/attachments/upload') {
        const payload = asRecord(await readJsonBody(req))
        const fileName = sanitizeOpenClawUploadFileName(normalizeText(payload?.fileName))
        const mimeType = normalizeText(payload?.mimeType) || 'application/octet-stream'
        const contentBase64 = normalizeText(payload?.contentBase64)
        if (!contentBase64) {
          setJson(res, 400, { error: 'Missing attachment content' })
          return
        }

        const decoded = decodeStrictBase64(contentBase64)
        if (decoded.length > OPENCLAW_UPLOAD_MAX_BYTES) {
          setJson(res, 400, { error: 'Attachment exceeds size limit (1000MB)' })
          return
        }

        await mkdir(OPENCLAW_UPLOAD_DIR, { recursive: true })
        const storedName = buildOpenClawUploadStoredName(fileName)
        const storedPath = join(OPENCLAW_UPLOAD_DIR, storedName)
        await writeFile(storedPath, decoded, { mode: 0o600 })

        setJson(res, 200, {
          ok: true,
          path: storedPath,
          fileName,
          mimeType,
          sizeBytes: decoded.length,
        })
        return
      }

      if (req.method === 'POST' && (url.pathname === '/openclaw-api/sessions/new-independent' || url.pathname === '/openclaw-api/sessions/new')) {
        const payload = asRecord(await readJsonBody(req))
        const label = normalizeText(payload?.label) || '新会话'
        const currentSessionKey = normalizeText(payload?.currentSessionKey)
        if (isOpenClawLightweightOnlyMode()) {
          const sessionKey = await createLightweightSession(label, currentSessionKey)
          setJson(res, 200, { sessionKey })
          return
        }
        if (OPENCLAW_NATIVE_STRICT_MODE && !(await isNativeOpenClawReady())) {
          setOpenClawNativeUnavailable(
            res,
            503,
            'OpenClaw gateway unavailable',
            'openclaw_native_unavailable',
          )
          return
        }
        const sessionKey = await (await isNativeOpenClawReady()
          ? createIndependentOpenClawSession(currentSessionKey, label)
          : createLightweightSession(label, currentSessionKey))
        setJson(res, 200, { sessionKey })
        return
      }

      if (req.method === 'POST' && url.pathname === '/openclaw-api/sessions/reset') {
        const payload = asRecord(await readJsonBody(req))
        const currentSessionKey = normalizeText(payload?.currentSessionKey)
        if (isOpenClawLightweightOnlyMode()) {
          const sessionKey = await resetLightweightSession(currentSessionKey)
          setJson(res, 200, { sessionKey })
          return
        }
        if (OPENCLAW_NATIVE_STRICT_MODE && !(await isNativeOpenClawReady())) {
          setOpenClawNativeUnavailable(
            res,
            503,
            'OpenClaw gateway unavailable',
            'openclaw_native_unavailable',
          )
          return
        }
        const sessionKey = await (await isNativeOpenClawReady()
          ? resetCurrentOpenClawSession(currentSessionKey)
          : resetLightweightSession(currentSessionKey))
        setJson(res, 200, { sessionKey })
        return
      }

      if (req.method === 'POST' && url.pathname === '/openclaw-api/sessions/rename') {
        const payload = asRecord(await readJsonBody(req))
        const sessionKey = normalizeText(payload?.sessionKey)
        const label = normalizeText(payload?.label)
        if (!sessionKey || !label) {
          setJson(res, 400, { error: 'Missing sessionKey or label' })
          return
        }
        if (isOpenClawLightweightOnlyMode()) {
          await renameLightweightSession(sessionKey, label)
          setJson(res, 200, { ok: true })
          return
        }
        if (OPENCLAW_NATIVE_STRICT_MODE && !(await isNativeOpenClawReady())) {
          setOpenClawNativeUnavailable(
            res,
            503,
            'OpenClaw gateway unavailable',
            'openclaw_native_unavailable',
          )
          return
        }
        if (await isNativeOpenClawReady()) {
          await runOpenClawGatewayCall('sessions.patch', {
            key: sessionKey,
            label,
          })
        } else {
          await renameLightweightSession(sessionKey, label)
        }
        setJson(res, 200, { ok: true })
        return
      }

      if (req.method === 'POST' && url.pathname === '/openclaw-api/sessions/delete') {
        const payload = asRecord(await readJsonBody(req))
        const sessionKey = normalizeText(payload?.sessionKey)
        if (!sessionKey) {
          setJson(res, 400, { error: 'Missing sessionKey' })
          return
        }
        if (isOpenClawLightweightOnlyMode()) {
          await deleteLightweightSession(sessionKey)
          setJson(res, 200, { ok: true })
          return
        }
        if (OPENCLAW_NATIVE_STRICT_MODE && !(await isNativeOpenClawReady())) {
          setOpenClawNativeUnavailable(
            res,
            503,
            'OpenClaw gateway unavailable',
            'openclaw_native_unavailable',
          )
          return
        }
        if (await isNativeOpenClawReady()) {
          await runOpenClawGatewayCall('sessions.delete', {
            key: sessionKey,
            deleteTranscript: true,
            emitLifecycleHooks: false,
          })
        } else {
          await deleteLightweightSession(sessionKey)
        }
        setJson(res, 200, { ok: true })
        return
      }

      if (req.method === 'GET' && url.pathname === '/claude-api/health') {
        const model = await loadClaudeModelConfigFromSharedPrefs()
        setJson(res, 200, {
          ok: true,
          data: {
            mode: 'claude-cli',
            modelConfigured: !!(model && model.apiKey && model.baseUrl && model.modelId),
          },
        })
        return
      }

      if (req.method === 'GET' && url.pathname === '/claude-api/sessions') {
        const limit = readPositiveInt(url.searchParams.get('limit'), 200)
        const sessions = await listClaudeSessions(limit)
        setJson(res, 200, { sessions })
        return
      }

      if (req.method === 'POST' && url.pathname === '/claude-api/history') {
        const payload = asRecord(await readJsonBody(req))
        const sessionKey = normalizeText(payload?.sessionKey)
        if (!sessionKey) {
          setJson(res, 400, { error: 'Missing sessionKey' })
          return
        }
        const limit = readPositiveInt(String(payload?.limit ?? ''), CLAUDE_SESSION_HISTORY_DEFAULT)
        const history = await readClaudeHistory(sessionKey, limit)
        setJson(res, 200, history)
        return
      }

      if (req.method === 'POST' && url.pathname === '/claude-api/send') {
        const payload = asRecord(await readJsonBody(req))
        const sessionKey = normalizeText(payload?.sessionKey)
        const message = normalizeText(payload?.message)
        const attachmentPaths = Array.isArray(payload?.attachmentPaths)
          ? payload.attachmentPaths.map((row) => normalizeText(row)).filter((row) => row.length > 0)
          : []
        if (!sessionKey || (!message && attachmentPaths.length === 0)) {
          setJson(res, 400, { error: 'Missing sessionKey and message/attachments' })
          return
        }
        const run = await sendClaudeMessage({
          sessionKey,
          message,
          attachmentPaths,
          allowSharedStorage: payload?.allowSharedStorage === true,
          dangerousMode: payload?.dangerousMode === true,
        })
        setJson(res, 200, { ok: true, runId: run.runId })
        return
      }

      if (req.method === 'POST' && url.pathname === '/claude-api/run/wait') {
        const payload = asRecord(await readJsonBody(req))
        const runId = normalizeText(payload?.runId)
        if (!runId) {
          setJson(res, 400, { error: 'Missing runId' })
          return
        }
        const waitTimeoutMs = normalizeTimeoutMs(
          payload?.timeoutMs,
          CLAUDE_RUN_WAIT_TIMEOUT_MS,
          2_000,
          120_000,
        )
        const startedAt = Date.now()
        while (Date.now() - startedAt < waitTimeoutMs) {
          const status = await getClaudeRunStatus(runId)
          if (status.completed === true) {
            setJson(res, 200, status)
            return
          }
          await sleepMs(250)
        }
        setJson(res, 200, await getClaudeRunStatus(runId))
        return
      }

      if (req.method === 'POST' && url.pathname === '/claude-api/run/abort') {
        const payload = asRecord(await readJsonBody(req))
        const requestedRunId = normalizeText(payload?.runId)
        const sessionKey = normalizeText(payload?.sessionKey)
        let targetRunId = requestedRunId
        if (!targetRunId && sessionKey) {
          let picked: ClaudeRunContext | null = null
          for (const run of claudeRuns.values()) {
            if (run.sessionKey !== sessionKey || run.completed) continue
            if (!picked || run.updatedAtMs > picked.updatedAtMs) {
              picked = run
            }
          }
          targetRunId = picked?.runId ?? ''
        }
        if (!targetRunId) {
          setJson(res, 400, { error: 'Missing runId or active session run' })
          return
        }
        const aborted = await abortClaudeRun(targetRunId)
        setJson(res, 200, {
          ok: aborted,
          aborted,
          status: aborted ? 'aborted' : 'failed',
          runId: targetRunId,
        })
        return
      }

      if (req.method === 'POST' && url.pathname === '/claude-api/attachments/upload-stream') {
        const fileName = sanitizeOpenClawUploadFileName(
          normalizeText(url.searchParams.get('fileName')) ||
          readHeaderText(req.headers['x-file-name']) ||
          'attachment.bin',
        )
        const contentType = readHeaderText(req.headers['content-type'])
        const mimeType =
          normalizeText(url.searchParams.get('mimeType')) ||
          readHeaderText(req.headers['x-mime-type']) ||
          (contentType.split(';')[0]?.trim() || 'application/octet-stream')
        await mkdir(CLAUDE_UPLOAD_DIR, { recursive: true })
        const storedName = buildOpenClawUploadStoredName(fileName)
        const storedPath = join(CLAUDE_UPLOAD_DIR, storedName)
        try {
          const sizeBytes = await writeOpenClawUploadStream(
            req,
            storedPath,
            CLAUDE_UPLOAD_STREAM_MAX_BYTES,
          )
          setJson(res, 200, {
            ok: true,
            path: storedPath,
            fileName,
            mimeType,
            sizeBytes,
          })
        } catch (error) {
          await unlink(storedPath).catch(() => undefined)
          const message = error instanceof Error ? error.message : 'Attachment upload failed'
          if (message.includes('Attachment exceeds size limit')) {
            setJson(res, 400, { error: message })
          } else {
            setJson(res, 500, { error: message })
          }
        }
        return
      }

      if (req.method === 'POST' && url.pathname === '/claude-api/attachments/upload') {
        const payload = asRecord(await readJsonBody(req))
        const fileName = sanitizeOpenClawUploadFileName(normalizeText(payload?.fileName))
        const mimeType = normalizeText(payload?.mimeType) || 'application/octet-stream'
        const contentBase64 = normalizeText(payload?.contentBase64)
        if (!contentBase64) {
          setJson(res, 400, { error: 'Missing attachment content' })
          return
        }
        const decoded = decodeStrictBase64(contentBase64)
        if (decoded.length > CLAUDE_UPLOAD_MAX_BYTES) {
          setJson(res, 400, { error: 'Attachment exceeds size limit (1000MB)' })
          return
        }
        await mkdir(CLAUDE_UPLOAD_DIR, { recursive: true })
        const storedName = buildOpenClawUploadStoredName(fileName)
        const storedPath = join(CLAUDE_UPLOAD_DIR, storedName)
        await writeFile(storedPath, decoded, { mode: 0o600 })
        setJson(res, 200, {
          ok: true,
          path: storedPath,
          fileName,
          mimeType,
          sizeBytes: decoded.length,
        })
        return
      }

      if (req.method === 'POST' && (url.pathname === '/claude-api/sessions/new-independent' || url.pathname === '/claude-api/sessions/new')) {
        const payload = asRecord(await readJsonBody(req))
        const label = normalizeText(payload?.label) || 'Claude 会话'
        const currentSessionKey = normalizeText(payload?.currentSessionKey)
        const sessionKey = await createClaudeSession(label, currentSessionKey)
        setJson(res, 200, { sessionKey })
        return
      }

      if (req.method === 'POST' && url.pathname === '/claude-api/sessions/reset') {
        const payload = asRecord(await readJsonBody(req))
        const currentSessionKey = normalizeText(payload?.currentSessionKey)
        if (!currentSessionKey) {
          setJson(res, 400, { error: 'Missing currentSessionKey' })
          return
        }
        const sessionKey = await resetClaudeSession(currentSessionKey)
        setJson(res, 200, { sessionKey })
        return
      }

      if (req.method === 'POST' && url.pathname === '/claude-api/sessions/rename') {
        const payload = asRecord(await readJsonBody(req))
        const sessionKey = normalizeText(payload?.sessionKey)
        const label = normalizeText(payload?.label)
        if (!sessionKey || !label) {
          setJson(res, 400, { error: 'Missing sessionKey or label' })
          return
        }
        await renameClaudeSession(sessionKey, label)
        setJson(res, 200, { ok: true })
        return
      }

      if (req.method === 'POST' && url.pathname === '/codex-api/server-requests/respond') {
        if (!appServer.isCommandAvailable()) {
          setJson(res, 503, { error: appServer.getUnavailableReason() || 'Codex CLI not installed' })
          return
        }
        const payload = await readJsonBody(req)
        await appServer.respondToServerRequest(payload)
        setJson(res, 200, { ok: true })
        return
      }

      if (req.method === 'GET' && url.pathname === '/codex-api/server-requests/pending') {
        if (!appServer.isCommandAvailable()) {
          setJson(res, 200, { data: [] })
          return
        }
        setJson(res, 200, { data: appServer.listPendingServerRequests() })
        return
      }

      if (req.method === 'GET' && url.pathname === '/codex-api/meta/methods') {
        if (!appServer.isCommandAvailable()) {
          setJson(res, 200, { data: [] })
          return
        }
        const methods = await methodCatalog.listMethods()
        setJson(res, 200, { data: methods })
        return
      }

      if (req.method === 'GET' && url.pathname === '/codex-api/meta/notifications') {
        if (!appServer.isCommandAvailable()) {
          setJson(res, 200, { data: [] })
          return
        }
        const methods = await methodCatalog.listNotificationMethods()
        setJson(res, 200, { data: methods })
        return
      }

      if (req.method === 'GET' && url.pathname === '/codex-api/events') {
        if (!appServer.isCommandAvailable()) {
          setJson(res, 503, { error: appServer.getUnavailableReason() || 'Codex CLI not installed' })
          return
        }
        res.statusCode = 200
        res.setHeader('Content-Type', 'text/event-stream; charset=utf-8')
        res.setHeader('Cache-Control', 'no-cache, no-transform')
        res.setHeader('Connection', 'keep-alive')
        res.setHeader('X-Accel-Buffering', 'no')

        let lastSentSequence = 0
        const writeNotification = (notification: SequencedNotification) => {
          if (res.writableEnded || res.destroyed) return
          if (notification.sequence <= lastSentSequence) return
          lastSentSequence = notification.sequence
          const payload = {
            method: notification.method,
            params: notification.params,
            atIso: new Date().toISOString(),
          }
          res.write(`id: ${String(notification.sequence)}\ndata: ${JSON.stringify(payload)}\n\n`)
        }

        const lastEventIdHeader = req.headers['last-event-id']
        const requestedSequence = Number(Array.isArray(lastEventIdHeader) ? lastEventIdHeader[0] : lastEventIdHeader)
        const hasReplayCursor = Number.isInteger(requestedSequence) && requestedSequence >= 0
        lastSentSequence = hasReplayCursor ? requestedSequence : appServer.getNotificationSequence()

        const unsubscribe = appServer.onNotification(writeNotification)
        if (hasReplayCursor) {
          for (const notification of appServer.listNotificationsAfter(requestedSequence)) {
            writeNotification(notification)
          }
        }

        res.write(`id: ${String(lastSentSequence)}\nevent: ready\ndata: ${JSON.stringify({ ok: true })}\n\n`)
        const keepAlive = setInterval(() => {
          res.write(': ping\n\n')
        }, 15000)

        const close = () => {
          clearInterval(keepAlive)
          unsubscribe()
          if (!res.writableEnded) {
            res.end()
          }
        }

        req.on('close', close)
        req.on('aborted', close)
        return
      }

      next()
    } catch (error) {
      const message = getErrorMessage(error, 'Unknown bridge error')
      setJson(res, 502, { error: message })
    }
  }

  middleware.dispose = () => {
    appServer.dispose()
  }

  return middleware
}
