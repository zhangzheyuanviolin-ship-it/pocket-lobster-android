import { createHash } from 'node:crypto'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawn } from 'node:child_process'
import { strict as assert } from 'node:assert'
import test from 'node:test'

test('phone UI MCP preserves UTF-8 task and binds status to taskId', async (t) => {
  const requests = []
  const server = createServer((request, response) => {
    let raw = ''
    request.setEncoding('utf8')
    request.on('data', (chunk) => { raw += chunk })
    request.on('end', () => {
      const body = JSON.parse(raw || '{}')
      requests.push({ url: request.url, contentType: request.headers['content-type'], body })
      response.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' })
      if (request.url === '/phone-agent/start') {
        response.end(JSON.stringify({
          ok: true,
          taskId: 'phone-task-roundtrip',
          taskSha256: body.taskSha256,
          status: 'starting',
          terminal: false,
          nextPollAfterMs: 2000,
          task: { id: 'phone-task-roundtrip', status: 'starting' },
        }))
      } else {
        response.end(JSON.stringify({
          ok: true,
          taskId: body.taskId,
          status: 'completed',
          terminal: true,
          result: '豆包已收到图片生成请求',
          task: { id: body.taskId, status: 'completed' },
        }))
      }
    })
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  t.after(() => server.close())

  const dir = mkdtempSync(join(tmpdir(), 'phone-ui-mcp-'))
  const script = join(dir, 'toolbox.cjs')
  const tokenFile = join(dir, 'bridge-token')
  writeFileSync(script, readFileSync('android/app/src/main/assets/anyclaw/claude-toolbox-server.js'))
  writeFileSync(tokenFile, 'roundtrip-token')
  const child = spawn(process.execPath, [script], {
    env: {
      ...process.env,
      ANYCLAW_HOST_BRIDGE_URL: `http://127.0.0.1:${server.address().port}`,
      ANYCLAW_SHARED_BRIDGE_TOKEN_FILE: tokenFile,
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  })
  t.after(() => child.kill())

  let output = ''
  const replies = new Map()
  child.stdout.setEncoding('utf8')
  child.stdout.on('data', (chunk) => {
    output += chunk
    while (output.includes('\n')) {
      const index = output.indexOf('\n')
      const line = output.slice(0, index).trim()
      output = output.slice(index + 1)
      if (!line) continue
      const message = JSON.parse(line)
      const resolve = replies.get(message.id)
      if (resolve) {
        replies.delete(message.id)
        resolve(message)
      }
    }
  })

  const rpc = (id, method, params) => new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      replies.delete(id)
      reject(new Error(`MCP response timeout for ${method}`))
    }, 5000)
    replies.set(id, (message) => {
      clearTimeout(timer)
      resolve(message)
    })
    child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`)
  })

  await rpc(1, 'initialize', {
    protocolVersion: '2024-11-05',
    capabilities: {},
    clientInfo: { name: 'roundtrip-test', version: '1' },
  })
  const task = '打开豆包APP，发送“帮我生成一张海边沙滩的图片”，等待并汇报真实结果。'
  const startReply = await rpc(2, 'tools/call', {
    name: 'phone_ui_agent_start',
    arguments: { task, mode: 'virtual', maxSteps: 30 },
  })
  const startResult = JSON.parse(startReply.result.content[0].text)
  assert.equal(startResult.ok, true)
  assert.equal(startResult.taskId, 'phone-task-roundtrip')

  const startRequest = requests[0]
  assert.equal(startRequest.url, '/phone-agent/start')
  assert.equal(startRequest.contentType, 'application/json; charset=utf-8')
  assert.equal(startRequest.body.task, task)
  assert.equal(Buffer.from(startRequest.body.taskBase64, 'base64').toString('utf8'), task)
  assert.equal(startRequest.body.taskSha256, createHash('sha256').update(task, 'utf8').digest('hex'))

  const statusReply = await rpc(3, 'tools/call', {
    name: 'phone_ui_agent_status',
    arguments: { taskId: startResult.taskId },
  })
  const statusResult = JSON.parse(statusReply.result.content[0].text)
  assert.equal(statusResult.terminal, true)
  assert.equal(statusResult.result, '豆包已收到图片生成请求')
  assert.equal(requests[1].url, '/phone-agent/status')
  assert.equal(requests[1].body.taskId, 'phone-task-roundtrip')
})
