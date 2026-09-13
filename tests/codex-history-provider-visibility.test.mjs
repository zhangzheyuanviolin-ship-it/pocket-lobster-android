import { readFileSync } from 'node:fs'
import { strict as assert } from 'node:assert'

const gateway = readFileSync('src/api/codexGateway.ts', 'utf8')
const schema = readFileSync('documentation/app-server-schemas/typescript/v2/ThreadListParams.ts', 'utf8')

assert.match(schema, /When present but empty, includes all providers/)
assert.match(
  gateway,
  /callRpc<ThreadListResponse>\('thread\/list',[\s\S]*?modelProviders:\s*\[\][\s\S]*?\}\)/,
)
