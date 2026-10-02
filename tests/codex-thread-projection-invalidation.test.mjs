import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { DatabaseSync } from 'node:sqlite'
import { invalidateThreadHistoryProjection } from '../src/server/codexThreadProjection.mjs'

test('invalidates only the route-migrated thread projection', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'codex-route-projection-'))
  const databasePath = join(directory, 'thread_history_1.sqlite')
  try {
    const database = new DatabaseSync(databasePath)
    for (const table of ['thread_items', 'thread_realtime_items', 'thread_turns']) {
      database.exec(`CREATE TABLE ${table} (thread_id TEXT NOT NULL, marker TEXT)`)
      database.prepare(`INSERT INTO ${table} VALUES (?, ?)`).run('target-thread', table)
      database.prepare(`INSERT INTO ${table} VALUES (?, ?)`).run('other-thread', table)
    }
    database.exec('CREATE TABLE thread_history_projection_state (thread_id TEXT NOT NULL, marker TEXT)')
    database.prepare('INSERT INTO thread_history_projection_state VALUES (?, ?)').run('target-thread', 'state')
    database.prepare('INSERT INTO thread_history_projection_state VALUES (?, ?)').run('other-thread', 'state')
    database.close()

    const result = await invalidateThreadHistoryProjection(databasePath, 'target-thread')
    assert.equal(result.deletedRows, 4)

    const verified = new DatabaseSync(databasePath, { readOnly: true })
    for (const table of ['thread_items', 'thread_realtime_items', 'thread_turns', 'thread_history_projection_state']) {
      assert.equal(verified.prepare(`SELECT count(*) count FROM ${table} WHERE thread_id = ?`).get('target-thread').count, 0)
      assert.equal(verified.prepare(`SELECT count(*) count FROM ${table} WHERE thread_id = ?`).get('other-thread').count, 1)
    }
    verified.close()
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
})

test('does not add read-path projection recovery that can stop active conversations', async () => {
  const source = await import('node:fs/promises').then(({ readFile }) => readFile('src/server/codexAppServerBridge.ts', 'utf8'))
  const readStart = source.indexOf('async function readThreadRecovered(')
  const readEnd = source.indexOf('\nasync function readPersistedThreadModel(', readStart)
  const readFunction = source.slice(readStart, readEnd)
  assert.doesNotMatch(readFunction, /dispose\(|waitUntilStopped|writeTextFileAtomic|invalidateThreadHistoryProjection/)
})
