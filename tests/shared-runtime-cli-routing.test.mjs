import test from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { execFile } from 'node:child_process'
import { copyFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { promisify } from 'node:util'

const execFileAsync = promisify(execFile)
const cliAsset = path.resolve('android/app/src/main/assets/shared-runtime/shared-runtime-cli.js')

async function withBridge(runClient) {
  const token = 'channel-isolated-test-token'
  let request
  const server = http.createServer((incoming, response) => {
    let body = ''
    incoming.setEncoding('utf8')
    incoming.on('data', (chunk) => { body += chunk })
    incoming.on('end', () => {
      request = {
        path: incoming.url,
        token: incoming.headers['x-pocket-lobster-token'],
        body: JSON.parse(body),
      }
      response.writeHead(200, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify({ ok: true, output: 'isolated bridge reached' }))
    })
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  try {
    const result = await runClient(server.address().port, token)
    return { result, request }
  } finally {
    await new Promise((resolve) => server.close(resolve))
  }
}

async function invokeClient(port, token, useEnvironmentUrl) {
  const root = mkdtempSync(path.join(os.tmpdir(), 'pocket-bridge-'))
  const home = path.join(root, 'home')
  const shared = path.join(root, 'shared-runtime')
  mkdirSync(home)
  mkdirSync(shared)
  const installedCli = path.join(root, 'shared-runtime-cli.cjs')
  copyFileSync(cliAsset, installedCli)
  writeFileSync(path.join(shared, 'bridge-token'), token)
  writeFileSync(path.join(shared, 'bridge-port'), String(port))
  const env = { ...process.env, HOME: home, ANYCLAW_AGENT_ID: 'codex' }
  delete env.ANYCLAW_MINIS_BRIDGE_URL
  delete env.ANYCLAW_SHARED_BRIDGE_TOKEN_FILE
  if (useEnvironmentUrl) env.ANYCLAW_MINIS_BRIDGE_URL = `http://127.0.0.1:${port}`
  try {
    return await execFileAsync(process.execPath, [installedCli, 'browser', 'list_tabs', '--json'], { env })
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
}

for (const useEnvironmentUrl of [true, false]) {
  test(`shared runtime CLI uses ${useEnvironmentUrl ? 'injected URL' : 'persisted channel port'}`, async () => {
    const { result, request } = await withBridge((port, token) =>
      invokeClient(port, token, useEnvironmentUrl))
    assert.match(result.stdout, /isolated bridge reached/)
    assert.equal(request.path, '/browser/call')
    assert.equal(request.token, 'channel-isolated-test-token')
    assert.equal(request.body.agent_id, 'codex')
    assert.equal(request.body.action, 'list_tabs')
  })
}
