import { strict as assert } from 'node:assert'
import { build } from 'esbuild'
import { test } from 'node:test'

const bundle = await build({
  entryPoints: ['src/api/codexRpcClient.ts'],
  bundle: true,
  write: false,
  platform: 'node',
  format: 'esm',
})
const rpc = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`)

test('JSON deadline remains active while the response body is being consumed', async () => {
  const originalFetch = globalThis.fetch
  try {
    globalThis.fetch = async (_input, init) => ({
      ok: true,
      status: 200,
      json: () => new Promise((_resolve, reject) => {
        init.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
      }),
    })
    const startedAt = Date.now()
    await assert.rejects(
      rpc.fetchJsonWithTimeout('/never-finishes', {}, 25),
      error => error instanceof DOMException && error.name === 'AbortError',
    )
    assert.ok(Date.now() - startedAt < 500)
  } finally {
    globalThis.fetch = originalFetch
  }
})
