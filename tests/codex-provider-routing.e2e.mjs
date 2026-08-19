import { strict as assert } from 'node:assert'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { copyFile, mkdtemp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const sleep = (delayMs) => new Promise((resolve) => setTimeout(resolve, delayMs))
const home = await mkdtemp(join(tmpdir(), 'pocket-route-e2e-'))
const stateDir = join(home, '.openclaw-android', 'state')
const codexDir = join(home, '.codex')
const requests = []
let responseIndex = 0

function responseEnvelope(model, text) {
  const sequence = ++responseIndex
  const responseId = `resp_test_${sequence}`
  const reasoning = {
    id: `rs_test_${sequence}`,
    type: 'reasoning',
    summary: [],
    content: [{ type: 'reasoning_text', text: `private-${sequence}` }],
    encrypted_content: `foreign-encrypted-content-${sequence}`,
    status: 'completed',
  }
  const message = {
    id: `msg_test_${sequence}`,
    type: 'message',
    status: 'completed',
    role: 'assistant',
    content: [{ type: 'output_text', annotations: [], logprobs: [], text }],
  }
  return {
    responseId,
    reasoning,
    message,
    response: {
      id: responseId,
      object: 'response',
      created_at: Math.floor(Date.now() / 1000),
      status: 'completed',
      background: false,
      error: null,
      incomplete_details: null,
      instructions: null,
      max_output_tokens: null,
      max_tool_calls: null,
      model,
      output: [reasoning, message],
      parallel_tool_calls: true,
      previous_response_id: null,
      prompt_cache_key: null,
      reasoning: { effort: 'medium', summary: null },
      safety_identifier: null,
      service_tier: 'default',
      store: false,
      temperature: 1,
      text: { format: { type: 'text' }, verbosity: 'medium' },
      tool_choice: 'auto',
      tools: [],
      top_logprobs: 0,
      top_p: 1,
      truncation: 'disabled',
      usage: { input_tokens: 10, input_tokens_details: { cached_tokens: 0 }, output_tokens: 5, output_tokens_details: { reasoning_tokens: 1 }, total_tokens: 15 },
      user: null,
      metadata: {},
    },
  }
}

function sendEvent(res, value) {
  res.write(`event: ${value.type}\n`)
  res.write(`data: ${JSON.stringify(value)}\n\n`)
}

const upstream = createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/v1/models') {
    res.setHeader('Content-Type', 'application/json')
    res.end(JSON.stringify({ data: [{ id: 'deepseek-v4-flash' }, { id: 'deepseek-v4-pro' }] }))
    return
  }
  if (req.method !== 'POST' || req.url !== '/v1/responses') {
    res.statusCode = 404
    res.end()
    return
  }
  const chunks = []
  for await (const chunk of req) chunks.push(chunk)
  const body = JSON.parse(Buffer.concat(chunks).toString('utf8'))
  requests.push(body)
  const model = body.model
  const outputText = `${model}-turn-${requests.length}`
  const envelope = responseEnvelope(model, outputText)
  if (body.stream !== true) {
    res.setHeader('Content-Type', 'application/json')
    res.end(JSON.stringify(envelope.response))
    return
  }
  res.setHeader('Content-Type', 'text/event-stream')
  sendEvent(res, { type: 'response.created', response: { ...envelope.response, status: 'in_progress', output: [], usage: null } })
  sendEvent(res, { type: 'response.output_item.added', output_index: 0, item: { ...envelope.reasoning, status: 'in_progress' }, sequence_number: 1 })
  sendEvent(res, { type: 'response.output_item.done', output_index: 0, item: envelope.reasoning, sequence_number: 2 })
  sendEvent(res, { type: 'response.output_item.added', output_index: 1, item: { ...envelope.message, status: 'in_progress', content: [] }, sequence_number: 3 })
  sendEvent(res, { type: 'response.content_part.added', item_id: envelope.message.id, output_index: 1, content_index: 0, part: { type: 'output_text', annotations: [], logprobs: [], text: '' }, sequence_number: 4 })
  sendEvent(res, { type: 'response.output_text.delta', item_id: envelope.message.id, output_index: 1, content_index: 0, delta: outputText, logprobs: [], sequence_number: 5 })
  sendEvent(res, { type: 'response.output_text.done', item_id: envelope.message.id, output_index: 1, content_index: 0, text: outputText, logprobs: [], sequence_number: 6 })
  sendEvent(res, { type: 'response.content_part.done', item_id: envelope.message.id, output_index: 1, content_index: 0, part: envelope.message.content[0], sequence_number: 7 })
  sendEvent(res, { type: 'response.output_item.done', output_index: 1, item: envelope.message, sequence_number: 8 })
  sendEvent(res, { type: 'response.completed', response: envelope.response, sequence_number: 9 })
  res.end()
})

await new Promise((resolve) => upstream.listen(0, '127.0.0.1', resolve))
const upstreamPort = upstream.address().port
const appPort = 48765
let serverLogs = ''
let app

async function requestJson(path, init) {
  const response = await fetch(`http://127.0.0.1:${appPort}${path}`, init)
  const payload = await response.json().catch(() => null)
  if (!response.ok) throw new Error(`${path} HTTP ${response.status}: ${JSON.stringify(payload)}`)
  return payload
}

async function rpc(method, params) {
  const payload = await requestJson('/codex-api/rpc', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ method, params }),
  })
  return payload.result
}

async function waitForServer() {
  const deadline = Date.now() + 30_000
  while (Date.now() < deadline) {
    try {
      await requestJson('/codex-api/availability')
      return
    } catch {
      await sleep(100)
    }
  }
  throw new Error(`Pocket Lobster server did not start: ${serverLogs}`)
}

async function waitForTurn(threadId, expectedTurns) {
  const deadline = Date.now() + 120_000
  while (Date.now() < deadline) {
    try {
      const result = await rpc('thread/read', { threadId, includeTurns: true })
      const turns = result?.thread?.turns ?? []
      if (turns.length >= expectedTurns) {
        const status = turns.at(-1)?.status
        if (status === 'failed') throw new Error(`Turn failed: ${JSON.stringify(turns.at(-1))}`)
        if (status !== 'inProgress') return result.thread
      }
    } catch (error) {
      const message = String(error)
      if (!message.includes('not materialized yet') && !message.includes('is empty')) throw error
    }
    await sleep(150)
  }
  throw new Error(`Timed out waiting for turn ${expectedTurns}`)
}

async function switchRoute(threadId, providerId, model) {
  return requestJson('/codex-api/thread-route', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ threadId, providerId, model }),
  })
}

function reasoningContents(value, found = []) {
  if (Array.isArray(value)) {
    for (const item of value) reasoningContents(item, found)
  } else if (value && typeof value === 'object') {
    if (value.type === 'reasoning' && Array.isArray(value.content)) found.push(value.content)
    for (const item of Object.values(value)) reasoningContents(item, found)
  }
  return found
}

function encryptedResponseItems(value, found = []) {
  if (Array.isArray(value)) {
    for (const item of value) encryptedResponseItems(item, found)
  } else if (value && typeof value === 'object') {
    if (
      (value.type === 'reasoning' || value.type === 'compaction') &&
      typeof value.encrypted_content === 'string' &&
      value.encrypted_content.length > 0
    ) found.push(value)
    for (const item of Object.values(value)) encryptedResponseItems(item, found)
  }
  return found
}

async function listRolloutFiles(directory) {
  const files = []
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) files.push(...await listRolloutFiles(path))
    else if (entry.isFile() && entry.name.endsWith('.jsonl')) files.push(path)
  }
  return files
}

try {
  await mkdir(stateDir, { recursive: true })
  await mkdir(codexDir, { recursive: true })
  const exportDir = process.env.E2E_DIAGNOSTICS_BLOCKED === '1' ? join(home, 'blocked-export') : join(home, 'shared')
  if (process.env.E2E_DIAGNOSTICS_BLOCKED === '1') await writeFile(exportDir, 'not-a-directory')
  if (process.env.E2E_OPENAI_AUTH_PATH) {
    await copyFile(process.env.E2E_OPENAI_AUTH_PATH, join(codexDir, 'auth.json'))
  }
  await writeFile(join(codexDir, 'config.toml'), 'approval_policy="never"\nsandbox_mode="read-only"\n')
  await writeFile(join(stateDir, 'codex-model-providers.json'), JSON.stringify({
    version: 1,
    currentConfigId: 'provider_alpha',
    configs: [
      { id: 'provider_alpha', providerId: 'pocket_provider_alpha', displayName: 'DeepSeek', baseUrl: `http://127.0.0.1:${upstreamPort}/v1`, modelId: 'deepseek-v4-flash', availableModelIds: ['deepseek-v4-flash', 'deepseek-v4-pro'], supportedReasoningEfforts: ['low', 'medium', 'high', 'xhigh'], upstreamProtocol: 'responses', verificationStatus: 'verified', isDefault: true },
      { id: 'provider_beta', providerId: 'pocket_provider_beta', displayName: 'DeepSeek Backup', baseUrl: `http://127.0.0.1:${upstreamPort}/v1`, modelId: 'deepseek-v4-pro', availableModelIds: ['deepseek-v4-pro'], supportedReasoningEfforts: ['low', 'medium', 'high', 'xhigh'], upstreamProtocol: 'responses', verificationStatus: 'verified', isDefault: false },
    ],
  }))
  app = spawn(process.execPath, ['dist-cli/index.js', '--port', String(appPort), '--no-password'], {
    env: {
      ...process.env,
      HOME: home,
      ANYCLAW_EXPORT_DIR: exportDir,
      POCKET_LOBSTER_CODEX_PROVIDER_ALPHA_API_KEY: 'alpha-key',
      POCKET_LOBSTER_CODEX_PROVIDER_BETA_API_KEY: 'beta-key',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  app.stdout.on('data', (chunk) => { serverLogs += chunk })
  app.stderr.on('data', (chunk) => { serverLogs += chunk })
  await waitForServer()

  const started = await rpc('thread/start', { cwd: home, model: 'deepseek-v4-flash', modelProvider: 'pocket_provider_alpha' })
  const threadId = started?.thread?.id
  assert.ok(threadId)
  await rpc('turn/start', { threadId, input: [{ type: 'text', text: 'turn one' }], model: 'deepseek-v4-flash', effort: 'medium' })
  await waitForTurn(threadId, 1)

  await switchRoute(threadId, 'pocket_provider_alpha', 'deepseek-v4-pro')
  await rpc('turn/start', { threadId, input: [{ type: 'text', text: 'turn two' }], model: 'deepseek-v4-pro', effort: 'high' })
  await waitForTurn(threadId, 2)

  let expectedTurns = 2
  let removedReasoningTotal = 0
  if (process.env.E2E_OPENAI_AUTH_PATH) {
    const openAiModel = process.env.E2E_OPENAI_MODEL || 'gpt-5.6-luna'
    const openAiRoute = await switchRoute(threadId, 'openai', openAiModel)
    assert.equal(openAiRoute.providerId, 'openai')
    assert.ok(openAiRoute.sanitizedReasoningItems >= 2)
    removedReasoningTotal += openAiRoute.sanitizedReasoningItems
    const confirmedOpenAiRoute = await requestJson(`/codex-api/thread-route?threadId=${encodeURIComponent(threadId)}`)
    assert.equal(confirmedOpenAiRoute.providerId, 'openai')
    await rpc('turn/start', {
      threadId,
      input: [{ type: 'text', text: 'Reply with exactly OPENAI_ROUTE_RETURN_OK.' }],
      model: openAiModel,
      effort: 'low',
    })
    await waitForTurn(threadId, ++expectedTurns)
    const returnedRoute = await switchRoute(threadId, 'pocket_provider_alpha', 'deepseek-v4-pro')
    assert.equal(returnedRoute.providerId, 'pocket_provider_alpha')
    removedReasoningTotal += returnedRoute.sanitizedReasoningItems
    await rpc('turn/start', {
      threadId,
      input: [{ type: 'text', text: 'turn after OpenAI' }],
      model: 'deepseek-v4-pro',
      effort: 'medium',
    })
    await waitForTurn(threadId, ++expectedTurns)
    const secondOpenAiRoute = await switchRoute(threadId, 'openai', openAiModel)
    assert.equal(secondOpenAiRoute.providerId, 'openai')
    assert.ok(secondOpenAiRoute.sanitizedReasoningItems >= 1)
    removedReasoningTotal += secondOpenAiRoute.sanitizedReasoningItems
    await rpc('turn/start', {
      threadId,
      input: [{ type: 'text', text: 'Reply with exactly OPENAI_SECOND_RETURN_OK.' }],
      model: openAiModel,
      effort: 'low',
    })
    await waitForTurn(threadId, ++expectedTurns)
    const secondReturnedRoute = await switchRoute(threadId, 'pocket_provider_alpha', 'deepseek-v4-pro')
    assert.equal(secondReturnedRoute.providerId, 'pocket_provider_alpha')
    removedReasoningTotal += secondReturnedRoute.sanitizedReasoningItems
  }

  const betaRoute = await switchRoute(threadId, 'pocket_provider_beta', 'deepseek-v4-pro')
  removedReasoningTotal += betaRoute.sanitizedReasoningItems
  await rpc('turn/start', { threadId, input: [{ type: 'text', text: 'turn three' }], model: 'deepseek-v4-pro', effort: 'low' })
  await waitForTurn(threadId, ++expectedTurns)

  const finalAlphaRoute = await switchRoute(threadId, 'pocket_provider_alpha', 'deepseek-v4-flash')
  assert.ok(finalAlphaRoute.sanitizedReasoningItems >= 1)
  removedReasoningTotal += finalAlphaRoute.sanitizedReasoningItems
  await rpc('turn/start', { threadId, input: [{ type: 'text', text: 'turn four' }], model: 'deepseek-v4-flash', effort: 'xhigh' })
  const finalThread = await waitForTurn(threadId, ++expectedTurns)
  const finalRoute = await requestJson(`/codex-api/thread-route?threadId=${encodeURIComponent(threadId)}`)

  const expectedModels = [
    'deepseek-v4-flash',
    'deepseek-v4-pro',
    ...(process.env.E2E_OPENAI_AUTH_PATH ? ['deepseek-v4-pro'] : []),
    'deepseek-v4-pro',
    'deepseek-v4-flash',
  ]
  assert.deepEqual(requests.map((request) => request.model), expectedModels)
  for (const request of requests.slice(1)) {
    for (const content of reasoningContents(request.input)) assert.deepEqual(content, [])
  }
  assert.equal(finalThread.modelProvider, 'pocket_provider_alpha')
  assert.ok(removedReasoningTotal >= 3)
  assert.deepEqual(finalRoute, { providerId: 'pocket_provider_alpha', model: 'deepseek-v4-flash' })
  const rolloutFiles = await listRolloutFiles(join(home, '.codex', 'sessions'))
  assert.equal(rolloutFiles.length, 1)
  const rollout = (await readFile(rolloutFiles[0], 'utf8')).trim().split('\n').map((line) => JSON.parse(line))
  for (const content of reasoningContents(rollout)) assert.deepEqual(content, [])
  assert.equal(encryptedResponseItems(rollout).length, 1)
  if (process.env.E2E_DIAGNOSTICS_BLOCKED !== '1') {
    await sleep(200)
    const diagnostics = (await readFile(join(exportDir, 'diagnostics', 'codex-chat-latest.jsonl'), 'utf8'))
      .trim().split('\n').map((line) => JSON.parse(line))
    assert.ok(diagnostics.some((event) => event.event === 'engine_initialized' && event.engine === 'codex app-server'))
    assert.ok(diagnostics.some((event) => event.event === 'rpc_success' && event.method === 'thread/start'))
    assert.equal(
      diagnostics.filter((event) => event.event === 'provider_response' && event.success === true).length,
      expectedModels.length,
    )
    assert.ok(diagnostics.some((event) => event.event === 'codex_notification' && event.method === 'turn/completed'))
  }
  console.log(JSON.stringify({ ok: true, turns: finalThread.turns.length, models: requests.map((request) => request.model), finalRoute }))
} finally {
  if (app && app.exitCode === null) app.kill('SIGTERM')
  await new Promise((resolve) => upstream.close(resolve))
  if (process.env.KEEP_E2E_HOME === '1') console.error(`E2E_HOME=${home}`)
  else await rm(home, { recursive: true, force: true })
}
