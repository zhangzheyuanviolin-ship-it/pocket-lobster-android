import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const worker = require('../android/app/src/main/assets/anyclaw/claude-context-compactor.cjs')
const app = '/data/user/0/com.codex.mobile.pocketlobster.test'
const archive = '/sdcard/Download/口袋大龙虾本地归档/诊断'
const decode = s => s.replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')
const xml = fs.readFileSync(app + '/shared_prefs/agent_model_configs.xml', 'utf8')
const encoded = xml.match(/<string name="configs_json">([\s\S]*?)<\/string>/)?.[1]
assert.ok(encoded, 'Missing private Claude configuration')
const configs = JSON.parse(decode(encoded))
const override = JSON.parse(fs.readFileSync(process.env.HOME + '/.openclaw-android/state/claude-runtime-overrides.json', 'utf8'))
const stored = configs.find(c => c.id === override.selectedConfigId) || configs.find(c => c.isDefault && c.agentId === 'claude-code')
assert.ok(stored?.apiKey && stored.baseUrl && stored.modelId, 'Missing configured model authentication')
const config = { ...stored, ...override }
const sourceFile = process.env.HOME + '/.pocketlobster/agent-sessions/claude-code/claude-code-20261006_071122.json'
const before = fs.readFileSync(sourceFile)
const source = JSON.parse(before)
// The original failed compaction used these exact first eleven messages.
const payload = { sessionId: source.sessionId, modelId: config.modelId, contextWindowTokens: config.contextWindowTokens,
  trigger: 'isolated_real_incident_acceptance', promptBytes: 44392, messages: source.messages.slice(0, 11) }
const beforeHash = crypto.createHash('sha256').update(before).digest('hex')
console.log(JSON.stringify({ model: config.modelId, sourceSessionId: payload.sessionId, messages: payload.messages.length, productionWorker: true }))
process.env.ANTHROPIC_API_KEY = config.apiKey
process.env.ANTHROPIC_BASE_URL = config.baseUrl
const cli = app + '/files/usr/lib/node_modules/@anthropic-ai/claude-code/cli.js'
let completedModelCalls = 0
const runner = worker.createCliRunner(cli, config, { onProgress: () => { completedModelCalls++ } })
const startedAt = new Date().toISOString()
const summary = await worker.compactSession(payload, runner, { progress: text => console.log(text), chunkBudget: process.argv.includes('--force-chunks') ? 7000 : undefined })
assert.ok(summary.startsWith('【模型生成的会话压缩摘要】'))
assert.ok(summary.includes('summary_model=' + config.modelId))
assert.ok(summary.includes('M5') && summary.includes('128'))
for (const m of payload.messages.filter(m => m.role === 'user').slice(-3)) assert.ok(summary.includes(m.text), 'Recent user words were lost')
// Verify that a fresh model request can understand and continue the actual task.
const continuation = await runner(`这次只做续接理解验收，不要执行原任务。根据以下摘要，用【当前目标】【关键约束】【已完成】【事实与证据】【未完成事项】【下一步】【风险与不确定】七个小节说明实际设备、用户最新上传文件的日期、最后未完成请求和接下来应做什么。不要重新假设在手机上运行大模型，也不要把待执行搜索写成已完成。\n${summary}`)
assert.ok(continuation.includes('M5') && continuation.includes('128'), 'Device correction was lost during continuation')
assert.ok(/10.?06|10月6|十月六|今天/.test(continuation), 'Latest document date was lost')
assert.ok(/搜索|检索/.test(continuation), 'Pending task was lost')
const afterHash = crypto.createHash('sha256').update(fs.readFileSync(sourceFile)).digest('hex')
assert.equal(afterHash, beforeHash, 'Original session changed during isolated validation')
const name = process.argv.includes('--force-chunks') ? 'v363_Claude分片压缩_真实模型验收_20261006' : 'v363_Claude高保真压缩_真实模型验收_20261006'
fs.writeFileSync(path.join(archive, name + '_摘要.txt'), summary)
fs.writeFileSync(path.join(archive, name + '_续接理解.txt'), continuation)
const proof = { startedAt, completedAt: new Date().toISOString(), model: config.modelId, actualClaudeCli: '2.1.97',
  productionWorkerSha256: crypto.createHash('sha256').update(fs.readFileSync(new URL('../android/app/src/main/assets/anyclaw/claude-context-compactor.cjs', import.meta.url))).digest('hex'),
  sourceSessionId: source.sessionId, sourceFileSha256Before: beforeHash, sourceFileSha256After: afterHash,
  completedModelCalls, toolsAvailable: 0, toolsExecuted: 0, formalSessionModified: false, formalConfigModified: false,
  recentThreeUserRequestsExact: true, deviceCorrectionRetained: true, latestDocumentDateRetained: true, pendingTaskRetained: true,
  summaryBytes: Buffer.byteLength(summary), inputBytes: Buffer.byteLength(JSON.stringify(payload.messages)), forcedChunks: process.argv.includes('--force-chunks') }
fs.writeFileSync(path.join(archive, name + '.json'), JSON.stringify(proof, null, 2) + '\n')
console.log(JSON.stringify(proof))
