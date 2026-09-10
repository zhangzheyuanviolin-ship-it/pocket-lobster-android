import { readFileSync } from 'node:fs'
import assert from 'node:assert/strict'
const read = p => readFileSync(p, 'utf8')
const root = 'android/'
for (const name of ['IShowerService', 'IShowerVideoSink']) {
  const path = `/src/main/java/com/ai/assistance/shower/${name}.java`
  assert.equal(read(root + 'showerclient' + path), read(root + 'showerserver' + path))
}
const capture = read(root + 'showerclient/src/main/java/com/ai/assistance/showerclient/ShowerFrameCapture.kt')
assert.match(capture, /current.timestamp \/ 1000 < expectedUs/)
assert.match(capture, /source.cropRect/)
assert.match(capture, /rowStride/)
assert.match(capture, /pixelStride/)
assert.match(capture, /image\?\.close\(\)/)
assert.doesNotMatch(capture, /PixelCopy|WindowManager|SurfaceView/)
const server = read(root + 'showerserver/src/main/java/com/ai/assistance/shower/Main.java')
assert.match(server, /if \(!alreadyThere && restoreRoot != null\)/)
assert.match(server, /moveRootTaskToDisplay/)
assert.match(server, /replayBytes > 8 \* 1024 \* 1024/)
assert.match(server, /synchronized \(deliveryLock\)/)
const evidence = read(root + 'app/src/main/java/com/codex/mobile/PhoneUiObservationJournal.kt')
assert.match(evidence, /MAX_OBSERVATIONS = 8/)
assert.match(evidence, /MAX_BYTES = 24L \* 1024 \* 1024/)
assert.match(evidence, /context.filesDir/)
assert.doesNotMatch(evidence, /apiKey|Authorization|https?:/)
