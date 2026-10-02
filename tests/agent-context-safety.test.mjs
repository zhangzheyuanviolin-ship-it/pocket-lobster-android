import { readFileSync } from 'node:fs'
import { strict as assert } from 'node:assert'

const serverManager = readFileSync('android/app/src/main/java/com/codex/mobile/CodexServerManager.kt', 'utf8')
const permissionManager = readFileSync('android/app/src/main/java/com/codex/mobile/PermissionManagerActivity.kt', 'utf8')
const claudeChat = readFileSync('android/app/src/main/java/com/codex/mobile/CliAgentChatActivity.kt', 'utf8')
const toolbox = readFileSync('android/app/src/main/assets/anyclaw/claude-toolbox-server.js', 'utf8')
const adapter = readFileSync('src/server/codexProviderAdapter.ts', 'utf8')

assert.match(serverManager, /private const val CLAUDE_CODE_VERSION = "2\.1\.112"/)
assert.match(serverManager, /claude-safe-update-/)
assert.match(serverManager, /verifyClaudeModelHandshake\(stagedCli, modelConfig\)/)
assert.match(serverManager, /verifyClaudeModelHandshake\(activeCli, modelConfig\)/)
assert.match(serverManager, /backupPackage\.renameTo\(livePackage\)/)
assert.doesNotMatch(serverManager, /install -g @anthropic-ai\/claude-code@\$CLAUDE_CODE_VERSION/)
assert.match(permissionManager, /应用更新不会自动更新 Claude/)

assert.match(claudeChat, /buildModelCompactionSummary/)
assert.match(claudeChat, /模型生成的会话压缩摘要/)
assert.match(claudeChat, /原会话已完整保留/)
assert.match(claudeChat, /CLAUDE_CODE_MAX_CONTEXT_TOKENS/)
assert.match(claudeChat, /CLAUDE_CODE_MAX_OUTPUT_TOKENS/)
assert.match(claudeChat, /CLAUDE_CODE_EFFORT_LEVEL/)
assert.match(claudeChat, /val recent = messages\n/)

assert.match(toolbox, /anyclaw_agent_model_config/)
assert.match(toolbox, /apiKeysExposed: false/)
assert.match(toolbox, /claude-runtime-overrides\.json/)

assert.match(adapter, /responses\(\?:\\\/compact\)\?/)
assert.match(adapter, /upstreamPath === 'responses\/compact'/)
assert.match(adapter, /128 \* 1024 \* 1024/)
