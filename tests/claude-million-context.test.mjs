import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import vm from 'node:vm'
import { spawn } from 'node:child_process'
import test from 'node:test'
import assert from 'node:assert/strict'

const toolbox = fs.readFileSync('android/app/src/main/assets/anyclaw/claude-toolbox-server.js', 'utf8')
const chat = fs.readFileSync('android/app/src/main/java/com/codex/mobile/CliAgentChatActivity.kt', 'utf8')
const scope = { URL }
vm.createContext(scope)
vm.runInContext(toolbox.slice(toolbox.indexOf('function claudeRuntimeCapabilities'), toolbox.indexOf('const SERVER_INFO')) + ';this.resolve = claudeRuntimeCapabilities;', scope)
const config = { id: 'm3', modelId: 'MiniMax-M3', baseUrl: 'https://api.minimaxi.com/anthropic', isDefault: true }
test('official M3 resolves 1M and the real CLI marker; numeric limits alone are insufficient', () => {
  const cap = scope.resolve(config)
  assert.equal(cap.contextWindowTokens, 1000000)
  assert.equal(cap.cliModelId, 'MiniMax-M3[1m]')
  assert.equal(cap.autoCompactTokens, 784080)
  assert.equal(scope.resolve({ ...config, baseUrl: 'https://gateway.example/anthropic', contextWindowTokens: 1000000 }).contextWindowTokens, 200000)
  assert.equal(scope.resolve({ ...config, baseUrl: 'https://api.minimaxi.com.evil.example/anthropic' }).millionContextSupported, false)
  assert.equal(scope.resolve({ ...config, modelId: 'MiniMax-M2.7' }).millionContextSupported, false)
})
test('legacy values recover to 1M without mutation and explicit lower windows stay lower', () => {
  const legacy = { contextWindowTokens: 524288, maxOutputTokens: 512000, autoCompactWindowTokens: 514000 }
  const copy = JSON.stringify(legacy)
  const cap = scope.resolve(config, legacy)
  assert.equal(cap.contextWindowTokens, 1000000)
  assert.equal(cap.maxOutputTokens, 128000)
  assert.equal(cap.autoCompactTokens, 706320)
  assert.equal(JSON.stringify(legacy), copy)
  const lower = scope.resolve(config, { contextWindowTokens: 100000, maxOutputTokens: 32768 })
  assert.equal(lower.cliModelId, 'MiniMax-M3')
  assert.equal(lower.maxOutputTokens, 25000)
})
test('only meaningful progress is rendered, while genuine errors and original tool records remain', () => {
  assert.doesNotMatch(chat, /当前 Claude CLI 的扩展上下文尚未验证|输出上限超过可用上下文预算|事件: \$phase|Claude 会话已初始化/)
  assert.match(chat, /"stream_event" ->/)
  assert.match(chat, /"error" -> appendClaudeProcessLine/)
  assert.match(chat, /工具调用:/)
  assert.match(chat, /ClaudeProcessPresentation\.prepareLine/)
  assert.match(chat, /runtimeCapabilities\(\)\.cliModelId/)
  assert.match(chat, /runtimeCapabilities\(\)\.safeInputTokens/)
  const ui = fs.readFileSync('android/app/src/main/java/com/codex/mobile/AgentModelManagerActivity.kt', 'utf8')
  assert.match(ui, /capabilityHint/)
  assert.match(ui, /userExplanation\(\)/)
  assert.match(ui, /contextInput\.error = "当前模型路线最多支持/)
})
test('natural-language MCP get/update/reset reports actual budgets and rejects fake expansion atomically', async t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocketlobster-v364-mcp-test-'))
  const state = path.join(dir, '.openclaw-android/state')
  fs.mkdirSync(state, { recursive: true })
  fs.writeFileSync(path.join(state, 'claude-model-configs.json'), JSON.stringify({ configs: [config, { ...config, id: 'gateway', baseUrl: 'https://gateway.example/anthropic', isDefault: false }] }))
  const overridePath = path.join(state, 'claude-runtime-overrides.json')
  fs.writeFileSync(overridePath, JSON.stringify({ contextWindowTokens: 524288, maxOutputTokens: 512000, autoCompactWindowTokens: 514000 }))
  const installedScript = path.join(dir, 'toolbox.cjs')
  fs.writeFileSync(installedScript, toolbox)
  const child = spawn(process.execPath, [installedScript], { env: { ...process.env, HOME: dir }, stdio: ['pipe', 'pipe', 'pipe'] })
  t.after(() => { child.kill(); fs.rmSync(dir, { recursive: true, force: true }) })
  const replies = new Map(); let pending = ''; let id = 0
  let stderr = ''
  child.stderr.on('data', b => { stderr += b.toString() })
  child.stdout.on('data', b => { pending += b; let at; while ((at = pending.indexOf('\n')) >= 0) { const line = pending.slice(0, at); pending = pending.slice(at + 1); if (!line.trim()) continue; const msg = JSON.parse(line); replies.get(msg.id)?.(msg) } })
  const rpc = (method, params) => new Promise((resolve, reject) => { const n = ++id; const timer = setTimeout(() => reject(new Error('MCP timeout: ' + stderr)), 5000); replies.set(n, r => { clearTimeout(timer); replies.delete(n); resolve(r) }); child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: n, method, params }) + '\n') })
  const call = async args => { const r = await rpc('tools/call', { name: 'anyclaw_agent_model_config', arguments: args }); assert.ok(r.result); return JSON.parse(r.result.content.find(c => c.type === 'text').text) }
  const get = await call({ action: 'get' })
  assert.equal(get.runtimeCapabilities.contextWindowTokens, 1000000)
  assert.equal(get.runtimeCapabilities.maxOutputTokens, 128000)
  const before = fs.readFileSync(overridePath, 'utf8')
  const rejected = await call({ action: 'update', contextWindowTokens: 2000000 })
  assert.equal(rejected.ok, false); assert.equal(rejected.maximum, 1000000)
  assert.equal(fs.readFileSync(overridePath, 'utf8'), before)
  const outputRejected = await call({ action: 'update', maxOutputTokens: 512000 })
  assert.equal(outputRejected.ok, false); assert.equal(outputRejected.maximum, 128000)
  assert.equal(fs.readFileSync(overridePath, 'utf8'), before)
  const valid = await call({ action: 'update', contextWindowTokens: 1000000, maxOutputTokens: 32768, autoCompactWindowTokens: 0 })
  assert.equal(valid.ok, true); assert.equal(valid.runtimeCapabilities.cliModelId, 'MiniMax-M3[1m]')
  assert.equal(valid.runtimeCapabilities.autoCompactTokens, 783457)
  const wrongRoute = await call({ action: 'update', selectedConfigId: 'gateway', contextWindowTokens: 1000000 })
  assert.equal(wrongRoute.ok, false); assert.equal(wrongRoute.maximum, 200000)
  const reset = await call({ action: 'reset' })
  assert.equal(reset.runtimeCapabilities.contextWindowTokens, 1000000)
  assert.equal(fs.readFileSync(overridePath, 'utf8'), '{}\n')
})
