export type CollaborationAgentId = 'codex' | 'claude' | 'minis'
export type CollaborationAgentStatus = 'idle' | 'pending' | 'planning' | 'running' | 'reviewing' | 'waiting_user' | 'completed' | 'failed' | 'aborted'
export type CollaborationRunStatus = 'planning' | 'running' | 'reviewing' | 'waiting_user' | 'completed' | 'failed' | 'aborted'

export type CollaborationEvent = {
  id: string
  atMs: number
  type: 'user' | 'decision' | 'assignment' | 'progress' | 'result' | 'final' | 'error' | 'control'
  agentId: CollaborationAgentId | ''
  text: string
}

export type CollaborationAgentState = {
  agentId: CollaborationAgentId
  role: 'leader' | 'worker'
  status: CollaborationAgentStatus
  startedAtMs: number
  updatedAtMs: number
  assignmentText: string
  actionText: string
  responseText: string
  errorText: string
}

export type CollaborationRun = {
  id: string
  title: string
  prompt: string
  leader: CollaborationAgentId
  status: CollaborationRunStatus
  createdAtMs: number
  updatedAtMs: number
  completedAtMs: number | null
  turnNumber: number
  roundNumber: number
  archived: boolean
  workspaceReady: boolean
  events: CollaborationEvent[]
  agents: Record<CollaborationAgentId, CollaborationAgentState>
  finalSummary: string
  errorText: string
}

async function readJson(response: Response): Promise<Record<string, unknown>> {
  const payload = await response.json().catch(() => null)
  if (!response.ok) {
    const message = payload && typeof payload === 'object' && typeof payload.error === 'string'
      ? payload.error
      : `协作服务请求失败：HTTP ${response.status}`
    throw new Error(message)
  }
  return payload as Record<string, unknown>
}

async function post(path: string, body: Record<string, unknown>): Promise<Record<string, unknown>> {
  return await readJson(await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify(body),
  }))
}

export function isCollaborationRunActive(run: CollaborationRun): boolean {
  return run.status === 'planning' || run.status === 'running' || run.status === 'reviewing'
}

export async function listCollaborationRuns(): Promise<CollaborationRun[]> {
  const payload = await readJson(await fetch('/collaboration-api/runs', { cache: 'no-store' }))
  return Array.isArray(payload.runs) ? payload.runs as CollaborationRun[] : []
}

export async function startCollaborationRun(leader: CollaborationAgentId, prompt: string): Promise<CollaborationRun> {
  const payload = await post('/collaboration-api/start', { leader, prompt: prompt.trim() })
  return payload.run as CollaborationRun
}

export async function continueCollaborationRun(runId: string, prompt: string): Promise<CollaborationRun> {
  const payload = await post('/collaboration-api/continue', { runId, prompt: prompt.trim() })
  return payload.run as CollaborationRun
}

export async function abortCollaborationRun(runId: string): Promise<CollaborationRun> {
  const payload = await post('/collaboration-api/abort', { runId })
  return payload.run as CollaborationRun
}

export async function renameCollaborationRun(runId: string, title: string): Promise<CollaborationRun> {
  const payload = await post('/collaboration-api/rename', { runId, title: title.trim() })
  return payload.run as CollaborationRun
}

export async function archiveCollaborationRun(runId: string, archived: boolean): Promise<CollaborationRun> {
  const payload = await post('/collaboration-api/archive', { runId, archived })
  return payload.run as CollaborationRun
}

export async function deleteCollaborationRun(runId: string): Promise<void> {
  await post('/collaboration-api/delete', { runId })
}
