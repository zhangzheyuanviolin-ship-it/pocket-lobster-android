import assert from 'node:assert/strict'
import test from 'node:test'

import {
  ensureCodexCollaborationThread,
  isMissingAgentSessionMessage,
  startCodexCollaborationTurnWithRecovery,
} from '../src/server/collaborationPolicy.js'

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
