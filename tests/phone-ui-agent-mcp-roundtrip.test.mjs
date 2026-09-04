import { readFileSync, writeFileSync, mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawn } from 'node:child_process'
import { strict as assert } from 'node:assert'
import test from 'node:test'

test('toolbox dynamically exposes browser tools but no delegated phone UI tools', async (t) => {
  const dir = mkdtempSync(join(tmpdir(), 'manual-only-toolbox-'))
  const script = join(dir, 'toolbox.cjs')
  writeFileSync(script, readFileSync('android/app/src/main/assets/anyclaw/claude-toolbox-server.js'))

  const child = spawn(process.execPath, [script], { stdio: ['pipe', 'pipe', 'pipe'] })
  t.after(() => child.kill())

  let output = ''
  let stderr = ''
  const replies = new Map()
  child.stdout.setEncoding('utf8')
  child.stderr.setEncoding('utf8')
  child.stderr.on('data', (chunk) => { stderr += chunk })
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

  const rpc = (id, method, params = {}) => new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      replies.delete(id)
      reject(new Error(`MCP response timeout for ${method}: ${stderr}`))
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
    clientInfo: { name: 'manual-only-test', version: '1' },
  })
  const listReply = await rpc(2, 'tools/list')
  const names = listReply.result.tools.map((tool) => tool.name)

  assert.ok(names.includes('minis_browser'))
  assert.ok(names.includes('anyclaw_terminal'))
  assert.ok(names.includes('anyclaw_alpine'))
  assert.equal(names.some((name) => name.startsWith('phone_ui_agent_')), false)
})
