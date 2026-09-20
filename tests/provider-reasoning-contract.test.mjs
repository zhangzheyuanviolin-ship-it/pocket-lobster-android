import { readFileSync } from 'node:fs'
import { strict as assert } from 'node:assert'

const adapter = readFileSync('src/server/codexProviderAdapter.ts', 'utf8')
const protocol = readFileSync('src/server/codexProviderProtocol.mjs', 'utf8')

assert.match(adapter, /reasoning_text is continuation state/)
assert.match(adapter, /return value\n}/)
assert.doesNotMatch(adapter, /sanitized\.content = \[\]/)
assert.match(protocol, /row\.type === 'response_item'[\s\S]*payload\?\.type\) === 'reasoning'[\s\S]*return \[\]/)
assert.match(protocol, /row\.ordinal === nextOrdinal[\s\S]*row\.ordinal = nextOrdinal/)
