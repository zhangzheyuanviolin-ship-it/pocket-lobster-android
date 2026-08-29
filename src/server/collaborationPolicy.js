export function isMissingAgentSessionMessage(message, agentId) {
  const normalized = typeof message === 'string' ? message.trim().toLowerCase() : ''
  if (!normalized) return false
  return agentId === 'codex'
    ? normalized.includes('thread not found') || normalized.includes('persisted thread not found')
    : agentId === 'minis'
      ? normalized.includes('session not found')
      : false
}

function errorMessage(error) {
  if (error instanceof Error) return error.message
  if (typeof error === 'string') return error
  return ''
}

export async function recoverCodexCollaborationThread(threadId, handlers) {
  try {
    const resumedThreadId = await handlers.resumeThread(threadId)
    return resumedThreadId || threadId
  } catch (error) {
    if (!isMissingAgentSessionMessage(errorMessage(error), 'codex')) throw error
    return await handlers.startThread(true)
  }
}

export async function ensureCodexCollaborationThread(threadId, handlers) {
  if (!threadId) return await handlers.startThread(false)
  try {
    await handlers.readThread(threadId)
    return threadId
  } catch (error) {
    if (!isMissingAgentSessionMessage(errorMessage(error), 'codex')) throw error
    return await recoverCodexCollaborationThread(threadId, handlers)
  }
}

export async function startCodexCollaborationTurnWithRecovery(threadId, handlers) {
  try {
    return { threadId, value: await handlers.startTurn(threadId) }
  } catch (error) {
    if (!isMissingAgentSessionMessage(errorMessage(error), 'codex')) throw error
    const recoveredThreadId = await recoverCodexCollaborationThread(threadId, handlers)
    return { threadId: recoveredThreadId, value: await handlers.startTurn(recoveredThreadId) }
  }
}
