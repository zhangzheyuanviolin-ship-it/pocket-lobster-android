import { readFileSync } from 'node:fs'
import { strict as assert } from 'node:assert'

const serverManager = readFileSync('android/app/src/main/java/com/codex/mobile/CodexServerManager.kt', 'utf8')
const setupScript = readFileSync('android/app/src/main/assets/setup-codex.sh', 'utf8')
const gradle = readFileSync('android/app/build.gradle.kts', 'utf8')
const workflow = readFileSync('.github/workflows/build-apk.yml', 'utf8')
const baselineScript = readFileSync('scripts/verify-openminis-baseline.sh', 'utf8')
const tsupConfig = readFileSync('tsup.config.ts', 'utf8')
const bundleScript = readFileSync('android/scripts/build-server-bundle.sh', 'utf8')
const packageJson = JSON.parse(readFileSync('package.json', 'utf8'))
const projectionSource = readFileSync('src/server/codexThreadProjection.mjs', 'utf8')

assert.match(serverManager, /private const val CODEX_VERSION = "0\.153\.4"/)
assert.match(serverManager, /model = "gpt-5\.6"/)
assert.match(serverManager, /codex-code-mode-host/)
assert.doesNotMatch(serverManager, /private const val CODEX_VERSION = "0\.137\.0"/)

assert.match(setupScript, /CODEX_VERSION="0\.153\.4"/)
assert.match(setupScript, /@openai\/codex@\$\{CODEX_VERSION\}/)

assert.match(gradle, /versionCode = 357/)
assert.match(gradle, /versionName = "1\.0\.116-codex-cli-0\.153\.4-gpt-6-astra-ready-openminis-1\.12-history-runtime-recovery-v357"/)
assert.match(gradle, /create\("operator"\)[\s\S]*applicationId = "com\.codex\.mobile\.pocketlobster\.test"/)
assert.match(workflow, /history-runtime-recovery-v357-beta/)
assert.match(workflow, /versionCode='357'/)
assert.match(workflow, /PACKAGE_ID="com\.codex\.mobile\.pocketlobster\.beta"/)
assert.match(baselineScript, /versionCode = 357/)

assert.equal(packageJson.engines.node, '>=22.5.0')
assert.match(tsupConfig, /target: 'node22'/)
assert.match(tsupConfig, /external: \['express', 'commander', 'node:sqlite'\]/)
assert.match(bundleScript, /grep -Fq 'import\("sqlite"\)'/)
assert.match(bundleScript, /grep -Fq 'node:sqlite'/)
assert.match(workflow, /grep -F 'node:sqlite'/)
assert.match(workflow, /grep -Fq 'import\("sqlite"\)'/)
assert.match(projectionSource, /const NODE_SQLITE_MODULE = 'node:sqlite'/)
assert.match(projectionSource, /import\(NODE_SQLITE_MODULE\)/)

assert.match(serverManager, /install -g --force @openai\/codex@\$CODEX_VERSION/)
assert.match(serverManager, /codex-\$CODEX_VERSION-linux-arm64\.tgz/)
assert.match(serverManager, /codex-cli \$CODEX_VERSION/)
assert.match(serverManager, /\$targetPkg\.staging/)
assert.doesNotMatch(serverManager, /Removing old Codex CLI/)
