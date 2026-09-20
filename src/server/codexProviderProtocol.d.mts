export type ProviderResponseContext = {
  customToolNames: Set<string>
  itemIds: Map<string, string>
  customItemIds: Set<string>
  functionArgumentBuffers: Map<string, string>
}

export function normalizeResponseItemId(type: unknown, value: unknown): string
export function repairResponseItemIds(value: unknown, itemIds?: Map<string, string>): {
  value: unknown
  repairedResponseItemIds: number
  itemIds: Map<string, string>
}
export function normalizePersistedThreadOrdinals(raw: string): { text: string; changed: boolean }
export function migratePersistedThreadText(raw: string, providerId: string, stripForeignProviderState?: boolean): {
  text: string
  changed: boolean
  providerMetadataFound: boolean
  sanitizedReasoningItems: number
  removedCompactionItems: number
  repairedResponseItemIds: number
}
export function prepareProviderRequest(value: unknown): { payload: unknown; customToolNames: string[] }
export function createProviderResponseContext(customToolNames?: string[]): ProviderResponseContext
export function sanitizeProviderResponse(value: unknown, context: ProviderResponseContext): unknown
