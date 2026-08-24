import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const read = (path) => readFile(new URL(`../${path}`, import.meta.url), 'utf8')

test('collaboration coordinator creates three real sessions and a leader synthesis turn', async () => {
  const server = await read('src/server/codexAppServerBridge.ts')
  assert.match(server, /runCodexCollaborationTurn/)
  assert.match(server, /runClaudeCollaborationTurn/)
  assert.match(server, /runMinisCollaborationTurn/)
  assert.match(server, /Promise\.allSettled/)
  assert.match(server, /buildCollaborationSynthesisPrompt/)
  assert.match(server, /chat\.prompt/)
  assert.match(server, /agent-sessions.*claude-code/s)
  assert.match(server, /turn\/interrupt/)
  assert.match(server, /chat\.session\.cancel/)
})

test('all three agent pages expose the collaboration switch and board', async () => {
  const app = await read('src/App.vue')
  const composer = await read('src/components/content/ThreadComposer.vue')
  const claudeLayout = await read('android/app/src/main/res/layout/activity_cli_agent_chat.xml')
  const minisTransform = await read('android/openminis/build.gradle.kts')
  assert.match(app, /CollaborationBoard/)
  assert.match(app, /listCollaborationRuns/)
  assert.match(composer, /三智能体协作/)
  assert.match(claudeLayout, /switchCliCollaboration/)
  assert.match(claudeLayout, /btnCliCollaborationBoard/)
  assert.match(minisTransform, /pocketLobsterCollaborationEnabled/)
  assert.match(minisTransform, /CollaborationClient\.startIfEnabled/)
})

test('shared Alpine and browser calls are isolated by agent identity', async () => {
  const sharedCli = await read('android/app/src/main/assets/shared-runtime/shared-runtime-cli.js')
  const bridge = await read('android/openminis/src/main/java/com/openminis/app/integration/MinisRuntimeBridge.kt')
  const manager = await read('android/app/src/main/java/com/codex/mobile/CodexServerManager.kt')
  const claude = await read('android/app/src/main/java/com/codex/mobile/CliAgentChatActivity.kt')
  assert.match(sharedCli, /ANYCLAW_AGENT_ID/)
  assert.match(sharedCli, /agent_id: agentId/)
  assert.match(bridge, /browserTabsByAgent/)
  assert.match(bridge, /sessionId = "\$SHARED_SESSION_ID-\$\{normalizeAgentId\(agentId\)\}"/)
  assert.match(manager, /"ANYCLAW_AGENT_ID" to "codex"/)
  assert.match(claude, /"ANYCLAW_AGENT_ID" to "claude"/)
})

test('Claude task ownership survives page destruction and remains abortable', async () => {
  const activity = await read('android/app/src/main/java/com/codex/mobile/CliAgentChatActivity.kt')
  const registry = await read('android/app/src/main/java/com/codex/mobile/AgentTaskRegistry.kt')
  assert.doesNotMatch(activity, /if \(isFinishing\) \{\s*activeProcess\?\.destroy\(\)/s)
  assert.match(activity, /AgentTaskRegistry\.isRunning/)
  assert.match(activity, /AgentTaskRegistry\.abort/)
  assert.match(activity, /taskStatusHandler\.postDelayed\(this, 900L\)/)
  assert.match(registry, /process\.destroyForcibly/)
})
