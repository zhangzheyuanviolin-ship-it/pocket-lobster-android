export type CollaborationAgentId = 'codex' | 'claude' | 'minis'

export type CollaborationAssignment = {
  agentId: CollaborationAgentId
  task: string
  expectedOutput: string
}

export type CollaborationDecision = {
  action: 'respond' | 'ask_user' | 'delegate'
  message: string
  assignments: CollaborationAssignment[]
  requiresSharedWorkspace: boolean
}

export function detectRequiredCollaborationTargets(
  userMessage: string,
  leader: CollaborationAgentId,
): CollaborationAgentId[]

export function decisionMissingRequiredTargets(
  decision: CollaborationDecision | null | undefined,
  requiredTargets: CollaborationAgentId[],
): boolean

export function enforceRequiredCollaborationDecision(
  decision: CollaborationDecision,
  requiredTargets: CollaborationAgentId[],
  userMessage: string,
): CollaborationDecision

export function isMissingAgentSessionMessage(
  message: string,
  agentId: 'codex' | 'minis',
): boolean

export type CodexThreadRecoveryHandlers = {
  readThread: (threadId: string) => Promise<unknown>
  resumeThread: (threadId: string) => Promise<string>
  startThread: (replacingMissingThread: boolean) => Promise<string>
}

export function recoverCodexCollaborationThread(
  threadId: string,
  handlers: Pick<CodexThreadRecoveryHandlers, 'resumeThread' | 'startThread'>,
): Promise<string>

export function ensureCodexCollaborationThread(
  threadId: string,
  handlers: CodexThreadRecoveryHandlers,
): Promise<string>

export function startCodexCollaborationTurnWithRecovery<T>(
  threadId: string,
  handlers: Pick<CodexThreadRecoveryHandlers, 'resumeThread' | 'startThread'> & {
    startTurn: (threadId: string) => Promise<T>
  },
): Promise<{ threadId: string; value: T }>
