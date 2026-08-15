import { readFileSync } from 'node:fs'
import { strict as assert } from 'node:assert'

const serverManager = readFileSync('android/app/src/main/java/com/codex/mobile/CodexServerManager.kt', 'utf8')
const setupScript = readFileSync('android/app/src/main/assets/setup-codex.sh', 'utf8')
const gradle = readFileSync('android/app/build.gradle.kts', 'utf8')

assert.match(serverManager, /private const val CODEX_VERSION = "0\.147\.0"/)
assert.match(serverManager, /model = "gpt-5\.6"/)
assert.match(serverManager, /codex-code-mode-host/)
assert.doesNotMatch(serverManager, /private const val CODEX_VERSION = "0\.137\.0"/)

assert.match(setupScript, /CODEX_VERSION="0\.147\.0"/)
assert.match(setupScript, /@openai\/codex@\$\{CODEX_VERSION\}/)

assert.match(gradle, /versionCode = 293/)
assert.match(gradle, /versionName = "1\.0\.53-codex-cli-0\.147\.0-gpt-5\.6-v293"/)
