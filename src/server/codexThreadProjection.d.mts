export function invalidateThreadHistoryProjection(
  databasePath: string,
  threadId: string,
  loadSqlite?: () => Promise<{ DatabaseSync: new (path: string) => unknown }>,
): Promise<{ deletedRows: number }>
