import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { once } from 'node:events'
import { createInterface } from 'node:readline'
import test from 'node:test'

const serverScript = new URL('../android/app/src/main/assets/anyclaw/collaboration-mcp-server.js', import.meta.url)

test('Claude collaboration MCP exposes every durable tool and forwards caller identity', async (context) => {
  const received = []
  const host = createServer((request, response) => {
    let body = ''
    request.setEncoding('utf8')
    request.on('data', (chunk) => { body += chunk })
    request.on('end', () => {
      received.push(JSON.parse(body))
      response.writeHead(200, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify({ ok: true, accepted: true }))
    })
  })
  host.listen(0, '127.0.0.1')
  await once(host, 'listening')
  const address = host.address()
  assert.ok(address && typeof address === 'object')

  const child = spawn(process.execPath, [serverScript.pathname], {
    env: {
      ...process.env,
      POCKET_LOBSTER_COLLABORATION_URL: `http://127.0.0.1:${address.port}`,
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  })
  const lines = createInterface({ input: child.stdout })
  const replies = []
  lines.on('line', (line) => replies.push(JSON.parse(line)))
  context.after(() => {
    lines.close()
    child.kill('SIGTERM')
    host.close()
  })

  const request = async (message) => {
    child.stdin.write(`${JSON.stringify(message)}\n`)
    const deadline = Date.now() + 3_000
    while (Date.now() < deadline) {
      const index = replies.findIndex((reply) => reply.id === message.id)
      if (index >= 0) return replies.splice(index, 1)[0]
      await new Promise((resolve) => setTimeout(resolve, 10))
    }
    throw new Error(`MCP response timed out for ${message.method}`)
  }

  const initialized = await request({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-11-25' } })
  assert.equal(initialized.result.serverInfo.name, 'pocket-lobster-collaboration')

  const listed = await request({ jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} })
  assert.deepEqual(
    listed.result.tools.map((tool) => tool.name),
    [
      'collaboration_delegate',
      'collaboration_delegate_many',
      'collaboration_status',
      'collaboration_wait',
      'collaboration_followup',
      'collaboration_cancel',
      'collaboration_finish',
    ],
  )
  for (const tool of listed.result.tools) {
    assert.ok(tool.inputSchema.required.includes('runId'))
    assert.ok(tool.inputSchema.required.includes('turnNumber'))
    assert.ok(tool.inputSchema.required.includes('leaderLeaseId'))
  }

  const called = await request({
    jsonrpc: '2.0',
    id: 3,
    method: 'tools/call',
    params: {
      name: 'collaboration_delegate',
      arguments: { runId: 'run-test', turnNumber: 3, leaderLeaseId: 'lease-test', agentId: 'codex', objective: 'ping' },
    },
  })
  assert.equal(called.result.isError, false)
  assert.deepEqual(received, [{
    callerAgentId: 'claude',
    tool: 'collaboration_delegate',
    arguments: { runId: 'run-test', turnNumber: 3, leaderLeaseId: 'lease-test', agentId: 'codex', objective: 'ping' },
  }])
})
