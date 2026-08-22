#!/usr/bin/env node
'use strict';

const fs = require('fs');
const http = require('http');
const path = require('path');

const mode = process.argv[2] || '';
const args = process.argv.slice(3);
const home = process.env.HOME || '';
const tokenPath = path.resolve(home, '..', 'shared-runtime', 'bridge-token');
const token = fs.readFileSync(tokenPath, 'utf8').trim();

function post(port, route, payload) {
  const body = JSON.stringify(payload);
  return new Promise((resolve, reject) => {
    const request = http.request({
      host: '127.0.0.1',
      port,
      path: route,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(body),
        'X-Pocket-Lobster-Token': token,
      },
    }, (response) => {
      let raw = '';
      response.setEncoding('utf8');
      response.on('data', (chunk) => { raw += chunk; });
      response.on('end', () => {
        try {
          const parsed = JSON.parse(raw || '{}');
          if (response.statusCode < 200 || response.statusCode >= 300) {
            reject(new Error(parsed.error || `HTTP ${response.statusCode}`));
            return;
          }
          resolve(parsed);
        } catch (error) {
          reject(new Error(`invalid bridge response: ${error.message}`));
        }
      });
    });
    request.setTimeout(910000, () => request.destroy(new Error('bridge timeout')));
    request.on('error', reject);
    request.end(body);
  });
}

function browserPayload(rawArgs) {
  if (rawArgs.length === 1 && rawArgs[0].trim().startsWith('{')) {
    return JSON.parse(rawArgs[0]);
  }
  const payload = { action: rawArgs.shift() || 'list_tabs' };
  while (rawArgs.length > 0) {
    const key = rawArgs.shift();
    if (!key || !key.startsWith('--')) throw new Error(`unexpected argument: ${key}`);
    const name = key.slice(2).replace(/-/g, '_');
    const value = rawArgs.shift();
    if (value === undefined) throw new Error(`missing value for ${key}`);
    payload[name] = /^-?\d+$/.test(value) ? Number(value) : value;
  }
  return payload;
}

(async () => {
  let result;
  if (mode === 'alpine') {
    const command = args[0] === '--command' ? args.slice(1).join(' ') : args.join(' ');
    result = await post(18927, '/alpine/exec', { command, timeout: 900 });
  } else if (mode === 'browser') {
    result = await post(18927, '/browser/call', browserPayload([...args]));
  } else {
    throw new Error('usage: alpine-shell <command> | minis-browser <action> [--key value]');
  }
  if (result.output) process.stdout.write(String(result.output) + '\n');
  else process.stdout.write(JSON.stringify(result, null, 2) + '\n');
  process.exitCode = Number.isInteger(result.exitCode) ? result.exitCode : (result.ok === false ? 1 : 0);
})().catch((error) => {
  process.stderr.write(`shared runtime bridge error: ${error.message}\n`);
  process.exitCode = 1;
});
