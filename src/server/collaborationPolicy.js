const COLLABORATION_AGENT_IDS = ['codex', 'claude', 'minis']

const NEGATED_INVOCATION_PATTERNS = [
  /(?:不要|不用|无需|不需要|禁止|别|暂不|先不|没有要求).{0,18}(?:联系|联络|调用|委派|发送|发消息|收发|通知|询问)/u,
  /(?:do not|don't|dont|no need to|must not).{0,30}(?:contact|message|delegate|invoke|ask)/iu,
]

const INVOCATION_PATTERN = /(?:联系|联络|调用|委派|分工|发送|发给|发消息|收发|通知|询问|确认.{0,12}收到|连通(?:性)?测试|(?:让|请|需要|要求).{0,12}(?:参与|处理|执行|协同|协作)|contact|message|delegate|invoke|ask|ping|connectivity)/iu
const ALL_WORKERS_PATTERN = /(?:其他|另外|其余|剩下)(?:的)?两个(?:字)?智能体|(?:全部|所有|三个|三名|3个)(?:的)?智能体|(?:both|all)(?:\s+of)?(?:\s+the)?\s+(?:other\s+)?agents?/iu

function mentionsAgent(message, agentId) {
  if (agentId === 'codex') return /\bcodex\b|代码智能体/iu.test(message)
  if (agentId === 'claude') return /\bclaude(?:\s+code)?\b|克劳德/iu.test(message)
  return /\bminis\b|\bopen\s*minis\b|迷你智能体/iu.test(message)
}

function isExplicitConnectivityRequest(message) {
  return /连通(?:性)?测试|收发消息|发送消息.{0,24}(?:回复|回执|收到)|(?:回复|回执).{0,24}发送消息|connectivity|\bping\b/iu.test(message)
}

export function detectRequiredCollaborationTargets(userMessage, leader) {
  const message = typeof userMessage === 'string' ? userMessage.trim() : ''
  if (!message || !COLLABORATION_AGENT_IDS.includes(leader)) return []
  if (NEGATED_INVOCATION_PATTERNS.some((pattern) => pattern.test(message))) return []
  if (!INVOCATION_PATTERN.test(message)) return []

  const workers = COLLABORATION_AGENT_IDS.filter((agentId) => agentId !== leader)
  if (ALL_WORKERS_PATTERN.test(message)) return workers

  const namedTargets = workers.filter((agentId) => mentionsAgent(message, agentId))
  if (namedTargets.length > 0) return namedTargets

  if (/(?:其他|另外|其余)(?:的)?智能体|other\s+agents?/iu.test(message)) return workers
  return []
}

function defaultAssignment(target, userMessage) {
  if (isExplicitConnectivityRequest(userMessage)) {
    return {
      agentId: target,
      task: '请向总调度明确回复您已收到本轮连通性测试消息，不要创建文件，不要执行无关任务。',
      expectedOutput: '返回一条明确、可核查的接收确认。',
    }
  }
  return {
    agentId: target,
    task: '根据用户的明确要求处理本轮协作子任务，完成后将可核查的结果直接回复总调度。',
    expectedOutput: '提供事实、执行结果、必要证据和风险。',
  }
}

export function decisionMissingRequiredTargets(decision, requiredTargets) {
  if (!Array.isArray(requiredTargets) || requiredTargets.length === 0) return false
  if (!decision || decision.action !== 'delegate' || !Array.isArray(decision.assignments)) return true
  const assigned = new Set(decision.assignments.map((assignment) => assignment?.agentId))
  return requiredTargets.some((target) => !assigned.has(target))
}

export function enforceRequiredCollaborationDecision(decision, requiredTargets, userMessage) {
  if (Array.isArray(requiredTargets) && requiredTargets.length > 0 && isExplicitConnectivityRequest(userMessage)) {
    return {
      action: 'delegate',
      message: '已按您的明确要求向指定协作成员发送连通性测试消息，收到双方回执后由总调度统一回复。',
      assignments: requiredTargets
        .filter((target) => COLLABORATION_AGENT_IDS.includes(target))
        .map((target) => defaultAssignment(target, userMessage)),
      requiresSharedWorkspace: false,
    }
  }
  if (!decisionMissingRequiredTargets(decision, requiredTargets)) return decision

  const existingAssignments = decision?.action === 'delegate' && Array.isArray(decision.assignments)
    ? decision.assignments.filter((assignment) => assignment && COLLABORATION_AGENT_IDS.includes(assignment.agentId))
    : []
  const assignments = [...existingAssignments]
  for (const target of requiredTargets) {
    if (!COLLABORATION_AGENT_IDS.includes(target)) continue
    if (assignments.some((assignment) => assignment.agentId === target)) continue
    assignments.push(defaultAssignment(target, userMessage))
  }

  return {
    action: 'delegate',
    message: decision?.action === 'delegate' && typeof decision.message === 'string' && decision.message.trim()
      ? decision.message
      : '已按您的明确要求向指定协作成员派发任务，收到结果后由总调度审核并统一回复。',
    assignments,
    requiresSharedWorkspace: decision?.action === 'delegate' && decision.requiresSharedWorkspace === true,
  }
}

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
