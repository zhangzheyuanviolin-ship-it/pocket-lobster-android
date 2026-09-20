import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { DatabaseSync } from 'node:sqlite'
import {
  invalidateThreadHistoryProjection,
  readThreadHistoryProjection,
  threadHistoryProjectionMatchesRollout,
} from '../src/server/codexThreadProjection.mjs'

test('invalidates only the rewritten thread history projection', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'codex-thread-projection-'))
  const databasePath = join(directory, 'thread_history_1.sqlite')
  try {
    const database = new DatabaseSync(databasePath)
    for (const table of ['thread_items', 'thread_realtime_items', 'thread_turns']) {
      database.exec(`CREATE TABLE ${table} (thread_id TEXT NOT NULL, marker TEXT)`)
      database.prepare(`INSERT INTO ${table} (thread_id, marker) VALUES (?, ?)`).run('target-thread', table)
      database.prepare(`INSERT INTO ${table} (thread_id, marker) VALUES (?, ?)`).run('other-thread', table)
    }
    database.exec('CREATE TABLE thread_history_projection_state (thread_id TEXT NOT NULL, next_rollout_byte_offset INTEGER NOT NULL, next_rollout_ordinal INTEGER NOT NULL)')
    database.prepare('INSERT INTO thread_history_projection_state VALUES (?, ?, ?)').run('target-thread', 0, 0)
    database.prepare('INSERT INTO thread_history_projection_state VALUES (?, ?, ?)').run('other-thread', 10, 1)
    database.close()

    const before = await readThreadHistoryProjection(databasePath, 'target-thread')
    assert.deepEqual(before, { nextRolloutByteOffset: 0, nextRolloutOrdinal: 0 })
    assert.equal(threadHistoryProjectionMatchesRollout('{"ordinal":0}\n', before), true)
    assert.equal(threadHistoryProjectionMatchesRollout('xxxxx{"ordinal":0}\n', { ...before, nextRolloutByteOffset: 5 }), false)
    assert.equal(threadHistoryProjectionMatchesRollout('{"ordinal":0}\n', { nextRolloutByteOffset: 14, nextRolloutOrdinal: 1 }), true)
    assert.equal(threadHistoryProjectionMatchesRollout('{"ordinal":0}\n', { nextRolloutByteOffset: 14, nextRolloutOrdinal: 7 }), false)

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
