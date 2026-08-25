export type CollaborationAgentId = 'codex' | 'claude' | 'minis'
export type CollaborationAgentStatus = 'pending' | 'running' | 'synthesizing' | 'completed' | 'failed' | 'aborted'

export type CollaborationAgentState = {
  agentId: CollaborationAgentId
  role: 'leader' | 'worker'
  status: CollaborationAgentStatus
  startedAtMs: number
  updatedAtMs: number
  actionText: string
  responseText: string
  errorText: string
}

export type CollaborationRun = {
  id: string
  title: string
  prompt: string
  leader: CollaborationAgentId
  status: 'running' | 'completed' | 'failed' | 'aborted'
  createdAtMs: number
  updatedAtMs: number
  completedAtMs: number | null
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

export async function listCollaborationRuns(): Promise<CollaborationRun[]> {
  const payload = await readJson(await fetch('/collaboration-api/runs', { cache: 'no-store' }))
  return Array.isArray(payload.runs) ? payload.runs as CollaborationRun[] : []
}

export async function startCollaborationRun(
  leader: CollaborationAgentId,
  prompt: string,
): Promise<CollaborationRun> {
  const payload = await readJson(await fetch('/collaboration-api/start', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ leader, prompt: prompt.trim() }),
  }))
  return payload.run as CollaborationRun
}

export async function abortCollaborationRun(runId: string): Promise<CollaborationRun> {
  const payload = await readJson(await fetch('/collaboration-api/abort', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ runId }),
  }))
  return payload.run as CollaborationRun
}
