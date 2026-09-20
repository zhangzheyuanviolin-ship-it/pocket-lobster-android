import { existsSync } from 'node:fs'

export async function invalidateThreadHistoryProjection(databasePath, threadId, loadSqlite = () => import('node:sqlite')) {
  if (!databasePath || !threadId) throw new Error('Missing thread history database path or thread id')
  if (!existsSync(databasePath)) return { deletedRows: 0 }
  const { DatabaseSync } = await loadSqlite()
  const database = new DatabaseSync(databasePath)
  let transactionOpen = false
  try {
    database.exec('BEGIN IMMEDIATE')
    transactionOpen = true
    let deletedRows = 0
    for (const table of ['thread_items', 'thread_realtime_items', 'thread_turns', 'thread_history_projection_state']) {
      const result = database.prepare(`DELETE FROM ${table} WHERE thread_id = ?`).run(threadId)
      deletedRows += Number(result.changes)
    }
    database.exec('COMMIT')
    transactionOpen = false
    return { deletedRows }
  } catch (error) {
    if (transactionOpen) {
      try { database.exec('ROLLBACK') } catch { /* preserve the original failure */ }
    }
    throw error
  } finally {
    database.close()
  }
}

export async function readThreadHistoryProjection(databasePath, threadId, loadSqlite = () => import('node:sqlite')) {
  if (!databasePath || !threadId || !existsSync(databasePath)) return null
  const { DatabaseSync } = await loadSqlite()
  const database = new DatabaseSync(databasePath, { readOnly: true })
  try {
    const row = database.prepare(
      'SELECT next_rollout_byte_offset, next_rollout_ordinal FROM thread_history_projection_state WHERE thread_id = ?',
    ).get(threadId)
    if (!row) return null
    return {
      nextRolloutByteOffset: Number(row.next_rollout_byte_offset),
      nextRolloutOrdinal: Number(row.next_rollout_ordinal),
    }
  } finally {
    database.close()
  }
}

export function threadHistoryProjectionMatchesRollout(raw, projection) {
  if (!projection) return true
  const bytes = Buffer.from(raw)
  const offset = projection.nextRolloutByteOffset
  if (!Number.isInteger(offset) || offset < 0 || offset > bytes.length) return false
  if (offset === bytes.length) {
    const lines = raw.trimEnd().split('\n')
    for (let index = lines.length - 1; index >= 0; index -= 1) {
      if (!lines[index].trim()) continue
      try {
        const row = JSON.parse(lines[index])
        return Number.isInteger(row?.ordinal) && row.ordinal + 1 === projection.nextRolloutOrdinal
      } catch {
        return false
      }
    }
    return projection.nextRolloutOrdinal === 0
  }
  if (offset > 0 && bytes[offset - 1] !== 10) return false
  const newline = bytes.indexOf(10, offset)
  const end = newline < 0 ? bytes.length : newline
  try {
    const row = JSON.parse(bytes.subarray(offset, end).toString('utf8'))
    return row?.ordinal === projection.nextRolloutOrdinal
  } catch {
    return false
  }
}
