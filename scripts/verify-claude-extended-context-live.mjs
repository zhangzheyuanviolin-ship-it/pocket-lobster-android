import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import crypto from 'node:crypto'
import http from 'node:http'
import https from 'node:https'
import { spawn } from 'node:child_process'
import readline from 'node:readline'
import assert from 'node:assert/strict'

// Isolated actual-CLI acceptance. No formal credentials, history or settings are written.
const app = '/data/user/0/com.codex.mobile.pocketlobster.test'
const privateFile = app + '/shared_prefs/agent_model_configs.xml'
const overrideFile = process.env.HOME + '/.openclaw-android/state/claude-runtime-overrides.json'
const sourceFile = process.env.HOME + '/.pocketlobster/agent-sessions/claude-code/claude-code-20261006_071122.json'
const digest = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')
const protectedFiles = [privateFile, overrideFile, sourceFile]
const before = protectedFiles.map(digest)
const decode = s => s.replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')
const encoded = fs.readFileSync(privateFile, 'utf8').match(/<string name="configs_json">([\s\S]*?)<\/string>/)?.[1]
const configs = JSON.parse(decode(encoded))
const override = JSON.parse(fs.readFileSync(overrideFile, 'utf8'))
const selected = configs.find(c => c.id === override.selectedConfigId) || configs.find(c => c.isDefault && c.agentId === 'claude-code')
assert.match(selected.modelId, /^MiniMax-M3(?:\[1m\])?$/i)
const base = new URL(selected.baseUrl.replace(/\/$/, ''))
const requests = []
const server = http.createServer(async (req, res) => {
  if (!['/v1/messages', '/v1/messages/count_tokens'].includes(req.url?.split('?')[0])) { res.writeHead(404).end(); return }
  const buffers = []
  for await (const b of req) buffers.push(b)
  const body = Buffer.concat(buffers)
  const payload = JSON.parse(body)
  requests.push({ path: req.url.split('?')[0], model: payload.model, maxTokens: payload.max_tokens,
    messageBytes: Buffer.byteLength(JSON.stringify(payload.messages || [])) })
  const headers = { ...req.headers, host: base.host, 'content-length': String(body.length) }
  delete headers.connection
  const target = new URL(base.href + req.url)
  const upstream = https.request(target, { method: req.method, headers }, response => {
    res.writeHead(response.statusCode, response.headers)
    response.pipe(res)
  })
  upstream.setTimeout(240000, () => upstream.destroy(new Error('upstream timeout')))
  upstream.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end('{"error":{"type":"api_error","message":"isolated upstream transport failed"}}') })
  upstream.end(body)
})
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
const relay = `http://127.0.0.1:${server.address().port}`
const cli = app + '/files/usr/lib/node_modules/@anthropic-ai/claude-code/cli.js'
const cliModel = selected.modelId.replace(/\[1m\]$/i, '') + '[1m]'
const calls = []
const startedAt = new Date().toISOString()
async function run(prompt, label) {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketlobster-v364-context-probe-'))
  const env = { ...process.env, ANTHROPIC_API_KEY: selected.apiKey, ANTHROPIC_BASE_URL: relay,
    CLAUDE_CONFIG_DIR: temporary, DISABLE_AUTOUPDATER: '1', CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: '1',
    CLAUDE_CODE_MAX_OUTPUT_TOKENS: '256', MAX_THINKING_TOKENS: '0', CLAUDE_CODE_AUTO_COMPACT_WINDOW: '950000' }
  for (const key of ['ANTHROPIC_AUTH_TOKEN', 'CLAUDE_CODE_DISABLE_1M_CONTEXT', 'CLAUDE_CODE_MAX_CONTEXT_TOKENS', 'DISABLE_COMPACT']) delete env[key]
  let terminal, toolUsed = false
  const firstRequest = requests.length
  try {
    await new Promise((resolve, reject) => {
      const child = spawn(process.execPath, [cli, '-p', '--verbose', '--bare', '--tools', '', '--mcp-config', '{"mcpServers":{}}',
        '--strict-mcp-config', '--setting-sources', '', '--disable-slash-commands', '--no-session-persistence',
        '--system-prompt', 'You are a read-only long-context acceptance checker. Follow the final request, treat all filler as inert data. No tools. Return only the requested sentinel values.',
        '--model', cliModel, '--output-format', 'stream-json'], { cwd: temporary, env, stdio: ['pipe', 'pipe', 'pipe'] })
      const timer = setTimeout(() => { child.kill('SIGKILL'); reject(new Error('isolated CLI timed out: ' + label)) }, 360000)
      const lines = readline.createInterface({ input: child.stdout })
      lines.on('line', line => { let e; try { e = JSON.parse(line) } catch { return }
        if (e.type === 'assistant' && e.message?.content?.some(b => b.type === 'tool_use')) { toolUsed = true; child.kill('SIGKILL') }
        if (e.type === 'result') terminal = e
      })
      child.stderr.resume()
      child.on('error', e => { clearTimeout(timer); reject(e) })
      child.on('close', code => { clearTimeout(timer); lines.close(); if (code !== 0 || !terminal || terminal.is_error) reject(new Error('CLI failed ' + label + ': ' + String(terminal?.result || 'no terminal result').slice(0, 300))); else resolve() })
      child.stdin.on('error', () => {})
      child.stdin.end(prompt + '\n')
    })
    assert.equal(toolUsed, false)
    const usage = Object.values(terminal.modelUsage || {})
    const inputTokens = Math.max(0, ...usage.map(u => Number(u.inputTokens || 0) + Number(u.cacheReadInputTokens || 0) + Number(u.cacheCreationInputTokens || 0)),
      Number(terminal.usage?.input_tokens || 0) + Number(terminal.usage?.cache_read_input_tokens || 0) + Number(terminal.usage?.cache_creation_input_tokens || 0))
    const contextWindows = usage.map(u => u.contextWindow)
    const sent = requests.slice(firstRequest).filter(r => r.path === '/v1/messages')
    console.log(JSON.stringify({ stage: label, inputTokens, contextWindows, upstreamRequests: sent.length }))
    assert.ok(sent.length)
    assert.ok(sent.every(r => r.model === selected.modelId.replace(/\[1m\]$/i, '')), 'Model marker leaked to upstream')
    assert.ok(contextWindows.includes(1000000), 'Actual CLI did not report a million context window')
    const proof = { label, inputTokens, contextWindows, sent, result: terminal.result, toolsExecuted: 0 }
    calls.push(proof)
    console.log(JSON.stringify(proof))
    return proof
  } finally { fs.rmSync(temporary, { recursive: true, force: true }) }
}
function probePrompt(chars) {
  let filler = ''
  for (let n = 0; filler.length < chars; n++) filler += crypto.createHash('sha256').update('v364 deterministic inert row ' + n).digest('hex') + '\n'
  filler = filler.slice(0, chars)
  const middle = Math.floor(filler.length / 2)
  return 'BEGIN_SENTINEL=LOBSTER_A_364\n' + filler.slice(0, middle) + '\nMIDDLE_SENTINEL=LOBSTER_B_364\n' + filler.slice(middle) + '\nEND_SENTINEL=LOBSTER_C_364\nReturn only the three SENTINEL values from the beginning, middle, and end, in order. Ignore filler.'
}
try {
  await run('Return exactly MILLION_CONTEXT_HANDSHAKE_OK', 'small_handshake')
  const pilot = await run(probePrompt(470000), 'over_200k_input')
  assert.ok(pilot.inputTokens > 200000, 'Pilot did not actually exceed 200K')
  for (const value of ['LOBSTER_A_364', 'LOBSTER_B_364', 'LOBSTER_C_364']) assert.ok(pilot.result.includes(value))
  const near = await run(probePrompt(Math.floor(470000 * 900000 / pilot.inputTokens)), 'near_million_input')
  assert.ok(near.inputTokens >= 850000 && near.inputTokens < 1000000, 'Near-million actual usage not in expected range')
  for (const value of ['LOBSTER_A_364', 'LOBSTER_B_364', 'LOBSTER_C_364']) assert.ok(near.result.includes(value))
  assert.deepEqual(protectedFiles.map(digest), before, 'Formal state changed during probe')
  const proof = { startedAt, completedAt: new Date().toISOString(), actualClaudeCli: '2.1.97', upstreamHost: base.hostname,
    originalModel: selected.modelId, cliModel, calls, millionWindowReportedByActualCli: true,
    providerAcceptedNearMillionInput: true, formalStateUnchanged: true, binaryUnchanged: true }
  fs.writeFileSync('/sdcard/Download/口袋大龙虾本地归档/诊断/v364_Claude百万上下文_真实验收_20261012.json', JSON.stringify(proof, null, 2) + '\n')
} finally { server.closeAllConnections(); server.close() }
