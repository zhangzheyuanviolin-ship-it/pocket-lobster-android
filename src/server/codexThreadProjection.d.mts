export function invalidateThreadHistoryProjection(
  databasePath: string,
  threadId: string,
  loadSqlite?: () => Promise<{ DatabaseSync: new (path: string) => unknown }>,
): Promise<{ deletedRows: number }>
export function readThreadHistoryProjection(
  databasePath: string,
  threadId: string,
  loadSqlite?: () => Promise<{ DatabaseSync: new (path: string, options?: { readOnly?: boolean }) => unknown }>,
): Promise<{ nextRolloutByteOffset: number; nextRolloutOrdinal: number } | null>
export function threadHistoryProjectionMatchesRollout(
  raw: string,
  projection: { nextRolloutByteOffset: number; nextRolloutOrdinal: number } | null,
): boolean
