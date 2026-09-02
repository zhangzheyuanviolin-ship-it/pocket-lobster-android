import { readFileSync } from 'node:fs'
import { strict as assert } from 'node:assert'

const adapter = readFileSync('src/server/codexProviderAdapter.ts', 'utf8')
const bridge = readFileSync('src/server/codexAppServerBridge.ts', 'utf8')

assert.match(adapter, /reasoning_text is continuation state/)
assert.match(adapter, /return value\n}/)
assert.doesNotMatch(adapter, /sanitized\.content = \[\]/)
assert.match(bridge, /row\.type === 'response_item'[\s\S]*payload\?\.type\) === 'reasoning'[\s\S]*return \[\]/)
