#!/usr/bin/env node
'use strict';

const fs = require('fs');
const http = require('http');
const path = require('path');

const mode = process.argv[2] || '';
const args = process.argv.slice(3);
const home = process.env.HOME || '';
const tokenPath = path.resolve(home, '..', 'shared-runtime', 'bridge-token');
const agentId = String(process.env.ANYCLAW_AGENT_ID || 'codex').trim().toLowerCase();

const BROWSER_ACTIONS = {
  navigate: 'Open a URL. Required: --url.',
  back: 'Go back in the current tab history.',
  forward: 'Go forward in the current tab history.',
  reload: 'Reload the current tab.',
  screenshot: 'Capture a PNG. Optional: --full-page, --output-path, --include-base64.',
  click: 'Click by --selector, --selector-type css|xpath|text, or --coordinate-x/--coordinate-y.',
  type: 'Enter --text in --selector. Optional: --selector-type css|xpath|text.',
  get_text: 'Read page text, optionally scoped by --selector.',
  get_readable: 'Extract the main readable page content.',
  get_page_info: 'Return URL, title, viewport, and page dimensions.',
  get_backbone: 'Return a compact DOM tree. Optional: --max-depth.',
  find_elements: 'Find elements by --selector. Optional: --selector-type css|xpath|text.',
  execute_js: 'Run JavaScript. Required: --script. Bare expressions and explicit return are both supported.',
  hover: 'Hover over a CSS --selector.',
  scroll: 'Scroll up or down. Optional: --direction, --amount, --selector.',
  scroll_and_collect: 'Collect repeated items. Required: --item-selector.',
  wait_for_dom_stable: 'Wait for page stability. Optional: --timeout seconds.',
  set_user_agent: 'Switch --user-agent mobile_chrome|desktop_chrome.',
  set_viewport: 'Set --viewport-width and --viewport-height, or --reset.',
  fetch: 'Fetch a URL with the current browser session. Required: --url.',
  new_tab: 'Create a tab, optionally with --url.',
  close_tab: 'Close --tab-id or the current tab.',
  list_tabs: 'List tabs and their IDs.',
  get_cookies: 'Read current-site cookie metadata.',
  set_cookies: 'Write cookies from --cookies JSON.',
};

const BROWSER_SCHEMA = {
  type: 'object',
  required: ['action'],
  properties: {
    action: { type: 'string', enum: Object.keys(BROWSER_ACTIONS) },
    url: { type: 'string', usedBy: ['navigate', 'fetch', 'new_tab'] },
    selector: { type: 'string', usedBy: ['click', 'type', 'get_text', 'scroll', 'hover', 'find_elements', 'scroll_and_collect'] },
    selector_type: { type: 'string', enum: ['css', 'xpath', 'text'], default: 'css', usedBy: ['click', 'type', 'get_text', 'hover', 'find_elements'] },
    text: { type: 'string', usedBy: ['type'] },
    script: { type: 'string', usedBy: ['execute_js'] },
    tab_id: { type: 'integer', description: 'Explicit shared tab handoff; any agent may continue work on this tab ID.' },
    direction: { type: 'string', enum: ['up', 'down'], usedBy: ['scroll'] },
    amount: { type: 'integer', usedBy: ['scroll'] },
    coordinate_x: { type: 'integer', usedBy: ['click'] },
    coordinate_y: { type: 'integer', usedBy: ['click'] },
    user_agent: { type: 'string', enum: ['mobile_chrome', 'desktop_chrome'], usedBy: ['set_user_agent'] },
    max_depth: { type: 'integer', usedBy: ['get_backbone'] },
    item_selector: { type: 'string', usedBy: ['scroll_and_collect'] },
    scroll_count: { type: 'integer', minimum: 1, maximum: 50, usedBy: ['scroll_and_collect'] },
    keywords: { type: 'array', items: { type: 'string' }, usedBy: ['get_cookies', 'scroll_and_collect'] },
    fuzzy: { type: 'boolean', usedBy: ['get_cookies'] },
    cookies: { type: 'array', items: { type: 'object' }, usedBy: ['set_cookies'] },
    timeout: { type: 'integer', unit: 'seconds', minimum: 1, maximum: 60 },
    viewport_width: { type: 'integer', minimum: 1, usedBy: ['set_viewport'] },
    viewport_height: { type: 'integer', minimum: 1, usedBy: ['set_viewport'] },
    reset: { type: 'boolean', usedBy: ['set_viewport'] },
    full_page: { type: 'boolean', usedBy: ['screenshot'] },
    output_path: { type: 'string', format: 'absolute PNG path', usedBy: ['screenshot'] },
    include_base64: { type: 'boolean', usedBy: ['screenshot'] },
  },
};

function browserHelp() {
  const actionLines = Object.entries(BROWSER_ACTIONS).map(([name, detail]) => `  ${name}: ${detail}`);
  return [
    'Usage: minis-browser <action> [--key value] [--json]',
    'Help: minis-browser --help | minis-browser schema',
    '',
    'The browser is the real visible OpenMinis WebView shared by Codex, Claude, and Minis.',
    'To continue another agent\'s page, run list_tabs and pass that exact --tab-id; explicit tab actions are serialized safely.',
    'Selectors default to CSS. Use --selector-type xpath or text for alternate lookup.',
    'Selector actions wait for the DOM and retry transient element-not-found failures.',
    'Screenshots are returned as PNG paths readable by the view_image tool.',
    'execute_js accepts both bare expressions such as document.title and scripts with an explicit return.',
    '',
    'Actions:',
    ...actionLines,
    '',
    'Examples:',
    '  minis-browser navigate --url https://example.com',
    '  minis-browser click --selector "Sign in" --selector-type text',
    '  minis-browser type --selector \'//input[@name="q"]\' --selector-type xpath --text openai',
    '  minis-browser screenshot --output-path /sdcard/Download/minis-page.png --json',
  ].join('\n');
}

function post(port, route, payload) {
  const token = fs.readFileSync(tokenPath, 'utf8').trim();
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
    if (['full_page', 'include_base64', 'reset'].includes(name) && (rawArgs.length === 0 || rawArgs[0].startsWith('--'))) {
      payload[name] = true;
      continue;
    }
    const value = rawArgs.shift();
    if (value === undefined) throw new Error(`missing value for ${key}`);
    if (value === 'true' || value === 'false') payload[name] = value === 'true';
    else if (/^-?\d+$/.test(value)) payload[name] = Number(value);
    else if ((value.startsWith('[') && value.endsWith(']')) || (value.startsWith('{') && value.endsWith('}'))) {
      payload[name] = JSON.parse(value);
    } else payload[name] = value;
  }
  return payload;
}

(async () => {
  let result;
  if (mode === 'alpine') {
    const command = args[0] === '--command' ? args.slice(1).join(' ') : args.join(' ');
    result = await post(18927, '/alpine/exec', { agent_id: agentId, command, timeout: 900 });
  } else if (mode === 'browser') {
    const browserArgs = [...args];
    const first = String(browserArgs[0] || '').toLowerCase();
    if (browserArgs.length === 0 || ['help', '--help', '-h'].includes(first)) {
      process.stdout.write(browserHelp() + '\n');
      return;
    }
    if (['actions', 'schema'].includes(first)) {
      process.stdout.write(JSON.stringify({ actions: BROWSER_ACTIONS, inputSchema: BROWSER_SCHEMA }, null, 2) + '\n');
      return;
    }
    const jsonIndex = browserArgs.indexOf('--json');
    const jsonOutput = jsonIndex >= 0;
    if (jsonOutput) browserArgs.splice(jsonIndex, 1);
    result = await post(18927, '/browser/call', { agent_id: agentId, ...browserPayload(browserArgs) });
    if (jsonOutput) {
      process.stdout.write(JSON.stringify(result, null, 2) + '\n');
      process.exitCode = result.ok === false ? 1 : 0;
      return;
    }
  } else {
    throw new Error('usage: alpine-shell <command> | minis-browser <action> [--key value]');
  }
  if (result.output) process.stdout.write(String(result.output) + '\n');
  if (result.imageFilePath) process.stdout.write(`image_path: ${result.imageFilePath}\n`);
  if (result.imageMimeType) process.stdout.write(`image_mime_type: ${result.imageMimeType}\n`);
  if (result.fetchedFilePath) process.stdout.write(`fetched_file_path: ${result.fetchedFilePath}\n`);
  if (result.pageURL) process.stdout.write(`page_url: ${result.pageURL}\n`);
  if (result.tabId !== undefined && result.tabId !== null) process.stdout.write(`tab_id: ${result.tabId}\n`);
  if (!result.output && !result.imageFilePath) process.stdout.write(JSON.stringify(result, null, 2) + '\n');
  process.exitCode = Number.isInteger(result.exitCode) ? result.exitCode : (result.ok === false ? 1 : 0);
})().catch((error) => {
  process.stderr.write(`shared runtime bridge error: ${error.message}\n`);
  process.exitCode = 1;
});
