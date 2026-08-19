import { readFileSync } from 'node:fs'
import { strict as assert } from 'node:assert'

const desktopState = readFileSync('src/composables/useDesktopState.ts', 'utf8')
const composer = readFileSync('src/components/content/ThreadComposer.vue', 'utf8')
const app = readFileSync('src/App.vue', 'utf8')

const newThreadFlow = desktopState.match(
  /async function sendMessageToNewThread[\s\S]*?\n  async function startTurnForThread/,
)?.[0] ?? ''

assert.ok(newThreadFlow)
assert.match(newThreadFlow, /const started = await startThread\(/)
assert.match(newThreadFlow, /threadId = started\.threadId/)
assert.match(newThreadFlow, /const actualProvider = started\.modelProvider/)
assert.doesNotMatch(newThreadFlow, /switchThreadRoute\(/)
assert.match(newThreadFlow, /await startTurnForThread\(threadId, nextText\)/)

assert.match(composer, /submit: \[text: string, complete: \(success: boolean\) => void\]/)
assert.match(composer, /emit\('submit', text, \(success\) =>/)
assert.match(composer, /if \(success && draft\.value\.trim\(\) === text\) draft\.value = ''/)

assert.match(app, /class="new-thread-error" aria-live="assertive"/)
assert.match(app, /async function onSubmitThreadMessage\(text: string, complete: \(success: boolean\) => void\)/)
assert.match(app, /complete\(true\)/)
assert.match(app, /complete\(false\)/)
const firstMessageSubmit = app.match(
  /async function submitFirstMessageForNewThread[\s\S]*?\n}\n<\/script>/,
)?.[0] ?? ''
assert.match(firstMessageSubmit, /await sendMessageToNewThread/)
assert.doesNotMatch(firstMessageSubmit, /catch\s*\{/)
