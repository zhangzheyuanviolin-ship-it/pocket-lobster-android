import type {
  Thread,
  ThreadItem,
  ThreadReadResponse,
  ThreadListResponse,
  UserInput,
} from '../appServerDtos'
import type { UiMessage, UiProjectGroup, UiThread } from '../../types/codex'

function toIso(seconds: number): string {
  return new Date(seconds * 1000).toISOString()
}

function toProjectName(cwd: string): string {
  const parts = cwd.split('/').filter(Boolean)
  return parts.at(-1) || cwd || 'unknown-project'
}

function toRawPayload(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

function extractCodexUserRequestText(value: string): string {
  const trimmed = value.trim()
  if (trimmed.startsWith('[三智能体协作任务')) {
    if (trimmed.includes('：总调度最终审核]')) return ''
    const originalRequestMarker = '用户原始请求：'
    const markerIndex = trimmed.lastIndexOf(originalRequestMarker)
    if (markerIndex >= 0) {
      return trimmed.slice(markerIndex + originalRequestMarker.length).trim()
    }
  }
  const markerRegex = /(?:^|\n)\s{0,3}#{0,6}\s*my request for codex\s*:?\s*/giu
  const matches = Array.from(value.matchAll(markerRegex))
  if (matches.length === 0) {
    return value.trim()
  }

  const lastMatch = matches.at(-1)
  if (!lastMatch || typeof lastMatch.index !== 'number') {
    return value.trim()
  }

  const markerOffset = lastMatch.index + lastMatch[0].length
  return value.slice(markerOffset).trim()
}

function parseUserMessageContent(
  itemId: string,
  turnId: string,
  turnIndex: number,
  content: UserInput[] | undefined,
): { text: string; images: string[]; rawBlocks: UiMessage[] } {
  if (!Array.isArray(content)) return { text: '', images: [], rawBlocks: [] }

  const textChunks: string[] = []
  const images: string[] = []
  const rawBlocks: UiMessage[] = []

  for (const [index, block] of content.entries()) {
    if (block.type === 'text' && typeof block.text === 'string' && block.text.length > 0) {
      textChunks.push(block.text)
    }
    if (block.type === 'image' && typeof block.url === 'string' && block.url.trim().length > 0) {
      images.push(block.url.trim())
    }

    if (block.type !== 'text' && block.type !== 'image') {
      rawBlocks.push({
        id: `${itemId}:user-content:${index}`,
        role: 'user',
        text: '',
        messageType: `userContent.${block.type}`,
        turnId,
        turnIndex,
        rawPayload: toRawPayload(block),
        isUnhandled: true,
      })
    }
  }

  return {
    text: extractCodexUserRequestText(textChunks.join('\n')),
    images,
    rawBlocks,
  }
}

function activityMessage(
  item: ThreadItem,
  turnId: string,
  turnIndex: number,
  text: string,
): UiMessage[] {
  return [{
    id: item.id,
    role: 'system',
    text,
    messageType: `activity.${item.type}`,
    turnId,
    turnIndex,
  }]
}

export function normalizeThreadItemV2(item: ThreadItem, turnId: string, turnIndex: number): UiMessage[] {
  if (item.type === 'agentMessage') {
    return [
      {
        id: item.id,
        role: 'assistant',
        text: item.text,
        messageType: item.type,
        turnId,
        turnIndex,
      },
    ]
  }

  if (item.type === 'userMessage') {
    const parsed = parseUserMessageContent(item.id, turnId, turnIndex, item.content as UserInput[] | undefined)
    const messages: UiMessage[] = []
    const hasRenderableUserContent = parsed.text.length > 0 || parsed.images.length > 0

    if (hasRenderableUserContent) {
      messages.push({
        id: item.id,
        role: 'user',
        text: parsed.text,
        images: parsed.images,
        messageType: item.type,
        turnId,
        turnIndex,
      })
    }

    messages.push(...parsed.rawBlocks)
    if (messages.length === 0) {
      return []
    }

    return messages
  }

  if (item.type === 'reasoning') {
    const summary = item.summary.map((part) => part.trim()).filter(Boolean).join('\n')
    return summary ? activityMessage(item, turnId, turnIndex, `思考：${summary}`) : []
  }

  return []
}

function pickThreadName(summary: Thread): string {
  const direct = [summary.preview]
  for (const candidate of direct) {
    if (typeof candidate === 'string' && candidate.trim().length > 0) {
      return candidate.trim()
    }
  }
  return ''
}

function stripMarkdownForDisplay(text: string): string {
  let result = text
  result = result.replace(/\[([^\]]+)\]\([^)]*\)/gu, '$1')
  result = result.replace(/\*\*(.+?)\*\*/gu, '$1')
  result = result.replace(/(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)/gu, '$1')
  result = result.replace(/~~(.+?)~~/gu, '$1')
  result = result.replace(/`([^`]+)`/gu, '$1')
  result = result.replace(/^#{1,6}\s+/gmu, '')
  result = result.replace(/\s+/gu, ' ')
  return result.trim()
}

function toThreadTitle(summary: Thread): string {
  const named = extractCodexUserRequestText(pickThreadName(summary))
  if (named.length === 0) return 'Untitled thread'
  return stripMarkdownForDisplay(named)
}

function toUiThread(summary: Thread): UiThread {
  return {
    id: summary.id,
    title: toThreadTitle(summary),
    projectName: toProjectName(summary.cwd),
    cwd: summary.cwd,
    createdAtIso: toIso(summary.createdAt),
    updatedAtIso: toIso(summary.updatedAt),
    preview: summary.preview,
    modelProvider: summary.modelProvider,
    unread: false,
    inProgress: false,
  }
}

function groupThreadsByProject(threads: UiThread[]): UiProjectGroup[] {
  const grouped = new Map<string, UiThread[]>()
  for (const thread of threads) {
    const rows = grouped.get(thread.projectName)
    if (rows) rows.push(thread)
    else grouped.set(thread.projectName, [thread])
  }

  return Array.from(grouped.entries())
    .map(([projectName, projectThreads]) => ({
      projectName,
      threads: projectThreads.sort(
        (a, b) => new Date(b.updatedAtIso).getTime() - new Date(a.updatedAtIso).getTime(),
      ),
    }))
    .sort((a, b) => {
      const aLast = new Date(a.threads[0]?.updatedAtIso ?? 0).getTime()
      const bLast = new Date(b.threads[0]?.updatedAtIso ?? 0).getTime()
      return bLast - aLast
    })
}

export function normalizeThreadGroupsV2(payload: ThreadListResponse): UiProjectGroup[] {
  const uiThreads = payload.data.map(toUiThread)
  return groupThreadsByProject(uiThreads)
}

export function normalizeThreadMessagesV2(payload: ThreadReadResponse): UiMessage[] {
  const turns = Array.isArray(payload.thread.turns) ? payload.thread.turns : []
  const messages: UiMessage[] = []
  for (let turnIndex = 0; turnIndex < turns.length; turnIndex += 1) {
    const turn = turns[turnIndex]
    const items = Array.isArray(turn.items) ? turn.items : []
    for (const item of items) {
      messages.push(...normalizeThreadItemV2(item, turn.id, turnIndex))
    }
  }
  return messages
}
