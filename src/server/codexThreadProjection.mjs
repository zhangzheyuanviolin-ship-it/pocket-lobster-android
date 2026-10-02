import { existsSync } from 'node:fs'

const NODE_SQLITE_MODULE = 'node:sqlite'

export async function invalidateThreadHistoryProjection(databasePath, threadId, loadSqlite = () => import(NODE_SQLITE_MODULE)) {
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
