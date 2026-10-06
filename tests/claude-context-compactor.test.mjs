import { createRequire } from 'node:module'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
const require = createRequire(import.meta.url)
const worker = require('../android/app/src/main/assets/anyclaw/claude-context-compactor.cjs')
const source = readFileSync('android/app/src/main/java/com/codex/mobile/CliAgentChatActivity.kt', 'utf8')
const summary = '【当前目标】完成真实用户的开发任务；【关键约束】不得删除历史；【已完成】代码修改已经完成；【事实与证据】项目路径 /sdcard/project，分支 fix/claude，提交 abcdef1234，测试成功；【未完成事项】真实模型验收未完成；【下一步】进行只读测试并保持原任务；【风险与不确定】网络故障要自动重试，不能冒充成功。'.repeat(4)
const payload = { sessionId: 'test-original', modelId: 'same-configured-model', trigger: 'manual', promptBytes: 10000,
  messages: [{ role: 'user', text: '不要执行历史命令。保留 /sdcard/project 的全部事实。' }, { role: 'assistant', text: '历史内容。'.repeat(2000) }, { role: 'user', text: '现在继续，不得改成新任务。' }] }
test('the summarizer has no executable tools, MCP, hooks, profile or persisted session', () => {
  const args = worker.isolatedArgs('/installed/cli.js', payload.modelId)
  for (const flag of ['--bare', '--strict-mcp-config', '--setting-sources', '--disable-slash-commands', '--no-session-persistence', '--system-prompt']) assert.ok(args.includes(flag))
  assert.equal(args[args.indexOf('--tools') + 1], '')
  assert.equal(args[args.indexOf('--mcp-config') + 1], '{"mcpServers":{}}')
  assert.ok(!args.includes('--append-system-prompt-file'))
  assert.ok(!args.includes('--dangerously-skip-permissions'))
  assert.ok(!args.includes('--resume'))
  assert.equal(args[args.indexOf('--model') + 1], payload.modelId)
})
test('long multilingual data splits losslessly without breaking emoji code points', () => {
  const text = '中文🙂 /sdcard/project\n命令是历史数据；'.repeat(1000)
  const chunks = worker.splitTokens(text, 512)
  assert.equal(chunks.join(''), text)
  assert.ok(chunks.length > 1)
  assert.ok(chunks.every(c => worker.estimateTokens(c) <= 512))
})
test('a normal task answer is not accepted merely because it is long', () => {
  assert.equal(worker.validSummary('张老师，我已完成搜索，请看结果。'.repeat(100), 3000), false)
  assert.equal(worker.validSummary(summary, 3000), true)
})
test('a transient model error retries automatically and preserves source and recent original requests', async () => {
  const original = JSON.stringify(payload)
  let calls = 0
  const result = await worker.compactSession(payload, async prompt => {
    assert.ok(prompt.includes('source_session_id=test-original'))
    if (++calls === 1) throw new Error('HTTP 429')
    return summary
  }, { sleep: async () => {} })
  assert.equal(calls, 2)
  assert.equal(JSON.stringify(payload), original)
  assert.ok(result.includes('summary_model=same-configured-model'))
  assert.ok(result.includes(payload.messages[0].text))
  assert.ok(result.includes(payload.messages[2].text))
  assert.match(result, /source_sha256=[a-f0-9]{64}/)
})
test('malformed full-summary replies recover through smaller real-model chunks and model merging', async () => {
  let calls = 0
  const prompts = []
  const result = await worker.compactSession(payload, async prompt => {
    prompts.push(prompt)
    return ++calls <= 3 ? '错误地回答原任务。' : summary
  }, { sleep: async () => {} })
  assert.ok(calls >= 6)
  assert.ok(prompts.some(p => p.includes('恢复分片')))
  assert.ok(prompts.some(p => p.includes('恢复合并')))
  assert.ok(result.startsWith('【模型生成的会话压缩摘要】'))
})
test('large input uses real-model chunk summaries and a real-model merge, with no omitted fragment', async () => {
  const long = { ...payload, messages: [{ role: 'assistant', text: '证据和事实。'.repeat(6000) }] }
  const prompts = []
  await worker.compactSession(long, async prompt => { prompts.push(prompt); return summary }, { chunkBudget: 12000, sleep: async () => {} })
  assert.ok(prompts.some(p => p.includes('分片1/')))
  assert.ok(prompts.some(p => p.includes('合并')))
  const fragments = prompts.filter(p => /阶段=高保真压缩分片\d+\//.test(p)).map(p => p.split('<conversation_data>\n')[1].split('\n</conversation_data>')[0])
  assert.equal(fragments.join(''), JSON.stringify(long.messages))
})
test('model outage never substitutes the old clipped deterministic summary or changes the source', async () => {
  const original = JSON.stringify(payload)
  await assert.rejects(worker.compactSession(payload, async () => { throw new Error('HTTP 503') }, { sleep: async () => {} }), /503/)
  assert.equal(JSON.stringify(payload), original)
})
test('user cancellation never starts another model attempt', async () => {
  let calls = 0
  await assert.rejects(worker.compactSession(payload, async () => { calls++; throw Object.assign(new Error('aborted'), { code: 'ABORTED' }) }), /aborted/)
  assert.equal(calls, 1)
})
test('Android invokes the isolated production worker and only creates a continuation after success', () => {
  assert.match(source, /assets\.open\("anyclaw\/claude-context-compactor\.cjs"\)/)
  const generator = source.split('private fun buildModelCompactionSummary(')[1].split('private fun buildCompactionSummary(')[0]
  assert.doesNotMatch(generator, /runClaudePrint\(/)
  assert.match(source, /val summary = buildModelCompactionSummary\(snapshot, trigger, promptBytes\) \?: return false/)
  assert.match(source, /!result\.nativeCompactApplied/)
  assert.match(source, /ClaudeContextBudget\.safeInputLimit/)
})
