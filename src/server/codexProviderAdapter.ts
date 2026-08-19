import { randomUUID } from 'node:crypto'
import { appendFile, mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import type { IncomingMessage, ServerResponse } from 'node:http'
import { dirname } from 'node:path'

type ProviderConfig = {
  id: string
  providerId: string
  displayName: string
  baseUrl: string
  modelId: string
}

type RuntimeStatus = {
  configId: string
  providerId: string
  displayName: string
  configuredModel: string
  requestedModel: string
  reportedModel: string
  upstreamProtocol: 'responses'
  success: boolean
  statusCode: number
  requestId: string
  checkedAt: string
  error: string
}

type AdapterOptions = {
  catalogPath: string
  runtimeStatusPath: string
  diagnosticPath?: string
}

let statusWriteChain: Promise<void> = Promise.resolve()

function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null
}

function text(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

async function readProviderConfig(path: string, configId: string): Promise<ProviderConfig | null> {
  if (!path || !configId) return null
  try {
    const parsed = asRecord(JSON.parse(await readFile(path, 'utf8')))
    const configs = Array.isArray(parsed?.configs) ? parsed.configs : []
    for (const item of configs) {
      const row = asRecord(item)
      if (!row || text(row.id) !== configId) continue
      const providerId = text(row.providerId)
      const baseUrl = text(row.baseUrl).replace(/\/+$/u, '')
      const modelId = text(row.modelId)
      if (!providerId || !baseUrl || !modelId) return null
      return {
        id: configId,
        providerId,
        displayName: text(row.displayName) || modelId,
        baseUrl,
        modelId,
      }
    }
  } catch {
    return null
  }
  return null
}

async function recordRuntimeStatus(path: string, status: RuntimeStatus, diagnosticPath = ''): Promise<void> {
  if (!path) return
  statusWriteChain = statusWriteChain.then(async () => {
    let providers: Record<string, unknown> = {}
    try {
      const parsed = asRecord(JSON.parse(await readFile(path, 'utf8')))
      providers = asRecord(parsed?.providers) ?? {}
    } catch {
      providers = {}
    }
    providers[status.configId] = status
    const output = JSON.stringify({ version: 1, providers }, null, 2)
    await mkdir(dirname(path), { recursive: true })
    const temp = `${path}.${process.pid}.tmp`
    await writeFile(temp, output, { encoding: 'utf8', mode: 0o600 })
    await rename(temp, path)
    if (diagnosticPath) {
      await mkdir(dirname(diagnosticPath), { recursive: true })
      await appendFile(diagnosticPath, `${JSON.stringify({
        at: status.checkedAt,
        event: 'provider_response',
        providerId: status.providerId,
        configuredModel: status.configuredModel,
        requestedModel: status.requestedModel,
        reportedModel: status.reportedModel,
        protocol: status.upstreamProtocol,
        success: status.success,
        statusCode: status.statusCode,
        requestId: status.requestId,
        error: status.error,
      })}\n`, { encoding: 'utf8', mode: 0o600 })
    }
  }).catch(() => undefined)
  await statusWriteChain
}

function setJson(res: ServerResponse, status: number, value: unknown): void {
  res.statusCode = status
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  res.end(JSON.stringify(value))
}

async function readJsonBody(req: IncomingMessage, maxBytes = 16 * 1024 * 1024): Promise<unknown> {
  const chunks: Buffer[] = []
  let size = 0
  for await (const chunk of req) {
    const value = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)
    size += value.length
    if (size > maxBytes) throw new Error('Provider request body is too large')
    chunks.push(value)
  }
  const raw = Buffer.concat(chunks).toString('utf8')
  return raw.trim() ? JSON.parse(raw) as unknown : {}
}

function responseModel(value: unknown): string {
  const record = asRecord(value)
  if (!record) return ''
  const response = asRecord(record.response)
  return text(response?.model) || text(record.model)
}

function responseError(value: unknown): string {
  const record = asRecord(value)
  const response = asRecord(record?.response)
  const error = asRecord(response?.error) ?? asRecord(record?.error)
  return text(error?.message) || text(error?.code)
}

export function sanitizeResponsesHistory(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sanitizeResponsesHistory)
  const record = asRecord(value)
  if (!record) return value
  const sanitized: Record<string, unknown> = {}
  for (const [key, child] of Object.entries(record)) {
    sanitized[key] = sanitizeResponsesHistory(child)
  }
  if (text(record.type) === 'reasoning' && Array.isArray(record.content)) {
    sanitized.content = []
  }
  return sanitized
}

function sanitizeResponseLine(line: string): string {
  const normalized = line.trim()
  if (!normalized.startsWith('data:')) return line
  const data = normalized.slice(5).trim()
  if (!data || data === '[DONE]') return line
  try {
    return `data: ${JSON.stringify(sanitizeResponsesHistory(JSON.parse(data) as unknown))}`
  } catch {
    return line
  }
}

function inspectResponseLine(line: string, observed: { model: string; error: string; requestId: string }): void {
  const normalized = line.trim()
  if (!normalized.startsWith('data:')) return
  const data = normalized.slice(5).trim()
  if (!data || data === '[DONE]') return
  try {
    const parsed = JSON.parse(data) as unknown
    observed.model = responseModel(parsed) || observed.model
    observed.error = responseError(parsed) || observed.error
    const record = asRecord(parsed)
    const response = asRecord(record?.response)
    observed.requestId = text(response?.id) || text(record?.id) || observed.requestId
  } catch {
    // Ignore partial or non-JSON SSE data while continuing to proxy it unchanged.
  }
}

async function proxyResponses(
  res: ServerResponse,
  provider: ProviderConfig,
  payload: unknown,
  authorization: string,
  options: AdapterOptions,
): Promise<void> {
  const request = asRecord(payload) ?? {}
  const requestedModel = text(request.model) || provider.modelId
  const localRequestId = `resp_${randomUUID().replace(/-/gu, '')}`
  const observed = { model: '', error: '', requestId: '' }
  const upstream = await fetch(`${provider.baseUrl}/responses`, {
    method: 'POST',
    headers: { Authorization: authorization, 'Content-Type': 'application/json' },
    body: JSON.stringify(sanitizeResponsesHistory(payload)),
  })

  if (!upstream.ok || !upstream.body) {
    const error = await upstream.text()
    await recordRuntimeStatus(options.runtimeStatusPath, {
      configId: provider.id,
      providerId: provider.providerId,
      displayName: provider.displayName,
      configuredModel: provider.modelId,
      requestedModel,
      reportedModel: '',
      upstreamProtocol: 'responses',
      success: false,
      statusCode: upstream.status || 502,
      requestId: localRequestId,
      checkedAt: new Date().toISOString(),
      error: error.slice(0, 500),
    }, options.diagnosticPath)
    setJson(res, upstream.status || 502, { error: { message: error || `Upstream HTTP ${upstream.status}` } })
    return
  }

  res.statusCode = upstream.status
  res.setHeader('Content-Type', upstream.headers.get('content-type') || 'text/event-stream; charset=utf-8')
  res.setHeader('Cache-Control', 'no-cache, no-transform')
  const reader = upstream.body.getReader()
  const decoder = new TextDecoder()
  let lineBuffer = ''
  let jsonBuffer = ''
  const isEventStream = (upstream.headers.get('content-type') || '').includes('text/event-stream')

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      const decoded = decoder.decode(value, { stream: true })
      if (isEventStream) {
        lineBuffer += decoded
        let lineEnd = lineBuffer.indexOf('\n')
        while (lineEnd >= 0) {
          const originalLine = lineBuffer.slice(0, lineEnd)
          const sanitizedLine = sanitizeResponseLine(originalLine)
          inspectResponseLine(sanitizedLine, observed)
          res.write(`${sanitizedLine}\n`)
          lineBuffer = lineBuffer.slice(lineEnd + 1)
          lineEnd = lineBuffer.indexOf('\n')
        }
      } else {
        jsonBuffer += decoded
      }
    }
    const decodedTail = decoder.decode()
    if (isEventStream) lineBuffer += decodedTail
    else jsonBuffer += decodedTail
    if (lineBuffer) {
      const sanitizedLine = sanitizeResponseLine(lineBuffer)
      inspectResponseLine(sanitizedLine, observed)
      res.write(sanitizedLine)
    }
    if (!isEventStream && jsonBuffer.trim()) {
      try {
        const parsed = sanitizeResponsesHistory(JSON.parse(jsonBuffer) as unknown)
        observed.model = responseModel(parsed)
        observed.error = responseError(parsed)
        const record = asRecord(parsed)
        observed.requestId = text(record?.id)
        res.write(JSON.stringify(parsed))
      } catch {
        observed.error = 'Upstream returned invalid Responses JSON'
        res.write(jsonBuffer)
      }
    }
    res.end()
    await recordRuntimeStatus(options.runtimeStatusPath, {
      configId: provider.id,
      providerId: provider.providerId,
      displayName: provider.displayName,
      configuredModel: provider.modelId,
      requestedModel,
      reportedModel: observed.model || requestedModel,
      upstreamProtocol: 'responses',
      success: !observed.error,
      statusCode: upstream.status,
      requestId: observed.requestId || localRequestId,
      checkedAt: new Date().toISOString(),
      error: observed.error,
    }, options.diagnosticPath)
  } catch (error) {
    if (!res.writableEnded) res.end()
    await recordRuntimeStatus(options.runtimeStatusPath, {
      configId: provider.id,
      providerId: provider.providerId,
      displayName: provider.displayName,
      configuredModel: provider.modelId,
      requestedModel,
      reportedModel: '',
      upstreamProtocol: 'responses',
      success: false,
      statusCode: 502,
      requestId: observed.requestId || localRequestId,
      checkedAt: new Date().toISOString(),
      error: error instanceof Error ? error.message : String(error),
    }, options.diagnosticPath)
  }
}

export async function handleCodexProviderAdapterRequest(
  req: IncomingMessage,
  res: ServerResponse,
  url: URL,
  options: AdapterOptions,
): Promise<boolean> {
  const match = /^\/codex-provider-adapter\/([^/]+)\/v1\/responses$/u.exec(url.pathname)
  if (!match) return false
  if (req.method !== 'POST') {
    setJson(res, 405, { error: { message: 'Method not allowed' } })
    return true
  }
  const configId = decodeURIComponent(match[1])
  const provider = await readProviderConfig(options.catalogPath, configId)
  if (!provider) {
    setJson(res, 404, { error: { message: 'Provider configuration not found' } })
    return true
  }
  const authorization = text(req.headers.authorization)
  if (!authorization.toLowerCase().startsWith('bearer ')) {
    setJson(res, 401, { error: { message: 'Provider API key is unavailable' } })
    return true
  }
  try {
    const payload = await readJsonBody(req)
    await proxyResponses(res, provider, payload, authorization, options)
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    await recordRuntimeStatus(options.runtimeStatusPath, {
      configId: provider.id,
      providerId: provider.providerId,
      displayName: provider.displayName,
      configuredModel: provider.modelId,
      requestedModel: provider.modelId,
      reportedModel: '',
      upstreamProtocol: 'responses',
      success: false,
      statusCode: 502,
      requestId: '',
      checkedAt: new Date().toISOString(),
      error: message.slice(0, 500),
    }, options.diagnosticPath)
    if (!res.headersSent) setJson(res, 502, { error: { message } })
    else if (!res.writableEnded) res.end()
  }
  return true
}
