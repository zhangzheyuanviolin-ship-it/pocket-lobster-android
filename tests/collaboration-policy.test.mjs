import assert from 'node:assert/strict'
import test from 'node:test'

import {
  decisionMissingRequiredTargets,
  detectRequiredCollaborationTargets,
  ensureCodexCollaborationThread,
  enforceRequiredCollaborationDecision,
  isMissingAgentSessionMessage,
  startCodexCollaborationTurnWithRecovery,
} from '../src/server/collaborationPolicy.js'

const agentIds = ['codex', 'claude', 'minis']

test('explicit three-agent connectivity requests target both workers for every leader', () => {
  const prompts = [
    '现在测试三智能体协作模式下的连通性，请给其他两个智能体发送消息并收到他们的回复。',
    '请马上联系另外两个智能体，确认双向收发消息正常。',
    'Run a connectivity test and message both other agents, then collect their replies.',
    '现在测试三智能体协作模式下的连通性。注意，没有额外具体任务，你只要确认你能收到我这条消息。并且确认你能给其他两个智能体发送消息，也能收到他们的回复，任务就算完成，最终由你统一向我汇报。',
  ]
  for (const leader of agentIds) {
    const expected = agentIds.filter((agentId) => agentId !== leader)
    for (const prompt of prompts) {
      assert.deepEqual(detectRequiredCollaborationTargets(prompt, leader), expected, `${leader}: ${prompt}`)
    }
  }
})

test('explicit named-agent requests target only the named workers', () => {
  assert.deepEqual(
    detectRequiredCollaborationTargets('请向Codex发送消息并让它回复。', 'claude'),
    ['codex'],
  )
  assert.deepEqual(
    detectRequiredCollaborationTargets('Please ask Claude Code and Minis to review this.', 'codex'),
    ['claude', 'minis'],
  )
})

test('ordinary messages and explicit non-delegation do not force workers', () => {
  assert.deepEqual(detectRequiredCollaborationTargets('能收到我这条消息吗？', 'claude'), [])
  assert.deepEqual(detectRequiredCollaborationTargets('这是一个简单问题，请您自己回答。', 'minis'), [])
  assert.deepEqual(detectRequiredCollaborationTargets('三智能体协作模式已打开，现在请直接回答二加二等于几。', 'codex'), [])
  assert.deepEqual(detectRequiredCollaborationTargets('不要联系其他两个智能体，直接回复。', 'codex'), [])
  assert.deepEqual(detectRequiredCollaborationTargets('No need to message the other agents.', 'claude'), [])
})

test('host enforcement replaces an incorrect direct response with deterministic delegation', () => {
  const prompt = '给其他两个智能体发送消息并确认收到回复。'
  const directResponse = {
    action: 'respond',
    message: '我没有跨智能体工具。',
    assignments: [],
    requiresSharedWorkspace: false,
  }
  for (const leader of agentIds) {
    const required = detectRequiredCollaborationTargets(prompt, leader)
    const expected = agentIds.filter((agentId) => agentId !== leader)
    assert.equal(decisionMissingRequiredTargets(directResponse, required), true)
    const corrected = enforceRequiredCollaborationDecision(directResponse, required, prompt)
    assert.equal(corrected.action, 'delegate')
    assert.deepEqual(corrected.assignments.map((assignment) => assignment.agentId), expected)
    assert.equal(corrected.requiresSharedWorkspace, false)
    assert.match(corrected.assignments[0].task, /不要创建文件/)
  }
})

test('host enforcement preserves valid assignments and only adds missing required workers', () => {
  const decision = {
    action: 'delegate',
    message: '先测试Codex。',
    assignments: [{ agentId: 'codex', task: '确认收到。', expectedOutput: '回执。' }],
    requiresSharedWorkspace: false,
  }
  const corrected = enforceRequiredCollaborationDecision(
    decision,
    ['codex', 'minis'],
    '请联系另外两个智能体确认连通性。',
  )
  assert.deepEqual(corrected.assignments.map((assignment) => assignment.agentId), ['codex', 'minis'])
  assert.equal(corrected.assignments[0].task, '确认收到。')
})

test('connectivity enforcement removes model-added work and always disables shared files', () => {
  const decision = {
    action: 'delegate',
    message: '让成员写文件证明连通性。',
    assignments: [
      { agentId: 'codex', task: '创建文件并运行环境自检。', expectedOutput: '文件。' },
      { agentId: 'minis', task: '创建文件并运行环境自检。', expectedOutput: '文件。' },
    ],
    requiresSharedWorkspace: true,
  }
  const corrected = enforceRequiredCollaborationDecision(
    decision,
    ['codex', 'minis'],
    '请给其他两个智能体发送消息，收到他们的回复即可完成连通性测试。',
  )
  assert.equal(corrected.requiresSharedWorkspace, false)
  assert.deepEqual(corrected.assignments.map((assignment) => assignment.agentId), ['codex', 'minis'])
  for (const assignment of corrected.assignments) {
    assert.match(assignment.task, /不要创建文件/)
    assert.doesNotMatch(assignment.task, /环境自检/)
  }
})

test('session recovery classification is narrow and does not hide unrelated failures', () => {
  assert.equal(isMissingAgentSessionMessage('thread not found: abc', 'codex'), true)
  assert.equal(isMissingAgentSessionMessage('Persisted thread not found: abc', 'codex'), true)
  assert.equal(isMissingAgentSessionMessage('Session not found', 'minis'), true)
  assert.equal(isMissingAgentSessionMessage('invalid encrypted content', 'codex'), false)
  assert.equal(isMissingAgentSessionMessage('provider authentication failed', 'minis'), false)
  assert.equal(isMissingAgentSessionMessage('codex app-server exited unexpectedly', 'codex'), false)
})

test('Codex collaboration thread recovery covers new, active, persisted and missing sessions', async () => {
  const calls = []
  const handlers = {
    readThread: async (threadId) => { calls.push(`read:${threadId}`) },
    resumeThread: async (threadId) => { calls.push(`resume:${threadId}`); return threadId },
    startThread: async (replacing) => { calls.push(`start:${replacing}`); return replacing ? 'replacement' : 'new' },
  }
  assert.equal(await ensureCodexCollaborationThread('', handlers), 'new')
  assert.deepEqual(calls.splice(0), ['start:false'])

  assert.equal(await ensureCodexCollaborationThread('active', handlers), 'active')
  assert.deepEqual(calls.splice(0), ['read:active'])

  handlers.readThread = async (threadId) => { calls.push(`read:${threadId}`); throw new Error(`thread not found: ${threadId}`) }
  assert.equal(await ensureCodexCollaborationThread('persisted', handlers), 'persisted')
  assert.deepEqual(calls.splice(0), ['read:persisted', 'resume:persisted'])

  handlers.resumeThread = async (threadId) => { calls.push(`resume:${threadId}`); throw new Error(`persisted thread not found: ${threadId}`) }
  assert.equal(await ensureCodexCollaborationThread('gone', handlers), 'replacement')
  assert.deepEqual(calls.splice(0), ['read:gone', 'resume:gone', 'start:true'])
})

test('Codex collaboration recovery propagates unrelated errors without replacing sessions', async () => {
  const calls = []
  await assert.rejects(
    ensureCodexCollaborationThread('thread-a', {
      readThread: async () => { calls.push('read'); throw new Error('provider authentication failed') },
      resumeThread: async () => { calls.push('resume'); return 'thread-a' },
      startThread: async () => { calls.push('start'); return 'replacement' },
    }),
    /provider authentication failed/,
  )
  assert.deepEqual(calls, ['read'])
})

test('Codex turn-start race recovers once and never replays after a second failure', async () => {
  const calls = []
  let attempts = 0
  const recovered = await startCodexCollaborationTurnWithRecovery('old-thread', {
    resumeThread: async (threadId) => { calls.push(`resume:${threadId}`); return 'resumed-thread' },
    startThread: async () => { calls.push('start'); return 'replacement' },
    startTurn: async (threadId) => {
      calls.push(`turn:${threadId}`)
      attempts += 1
      if (attempts === 1) throw new Error('thread not found: old-thread')
      return { id: 'turn-ok' }
    },
  })
  assert.equal(recovered.threadId, 'resumed-thread')
  assert.deepEqual(recovered.value, { id: 'turn-ok' })
  assert.deepEqual(calls, ['turn:old-thread', 'resume:old-thread', 'turn:resumed-thread'])

  attempts = 0
  const failedCalls = []
  await assert.rejects(
    startCodexCollaborationTurnWithRecovery('old-thread', {
      resumeThread: async () => { failedCalls.push('resume'); return 'resumed-thread' },
      startThread: async () => { failedCalls.push('start'); return 'replacement' },
      startTurn: async (threadId) => {
        failedCalls.push(`turn:${threadId}`)
        attempts += 1
        throw new Error(`thread not found on attempt ${attempts}`)
      },
    }),
    /thread not found on attempt 2/,
  )
  assert.deepEqual(failedCalls, ['turn:old-thread', 'resume', 'turn:resumed-thread'])
})
