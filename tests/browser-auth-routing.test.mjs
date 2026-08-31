import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const read = (path) => readFile(new URL(`../${path}`, import.meta.url), 'utf8')

test('all shared-browser Google authentication entry points leave WebView', async () => {
  const integration = await read('android/openminis/build.gradle.kts')
  const manager = await read('third_party/OpenMinis/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt')
  const router = await read('third_party/OpenMinis/src/android/app/src/main/java/com/openminis/app/browser/GoogleAuthRouter.kt')
  const preview = await read('third_party/OpenMinis/src/android/app/src/main/java/com/openminis/app/ui/preview/WebViewHolder.kt')

  assert.match(integration, /GoogleAuthRouter\.shouldRouteExternally\(normalized\)/)
  assert.match(integration, /GoogleAuthRouter\.openInCustomTab/)
  assert.match(integration, /Chrome cookies are isolated from this automated WebView/)
  assert.match(integration, /GoogleAuthRouter\.shouldRouteExternally\(currentUrl\)/)
  assert.match(integration, /GoogleAuthRouter\.openInCustomTab\(webView\.context, currentUrl\)/)
  assert.doesNotMatch(integration, /GoogleAuthRouter\.openInCustomTab\(appContext, currentUrl\)/)
  assert.match(integration, /context !is Activity/)
  assert.match(integration, /Intent\.FLAG_ACTIVITY_NEW_TASK/)
  assert.match(integration, /check\("Chrome cookies are isolated from this automated WebView" in browserManager\)/)
  assert.match(manager, /if \(!normalized\.contains\(":\/\/"\)\) normalized = "https:\/\/\$normalized"\s+val deferred = CompletableDeferred<Unit>\(\)/)
  assert.match(manager, /fun loadURL\(urlString: String\)[\s\S]*?_isLoading\.value = true[\s\S]*?webView\.loadUrl\(normalized\)/)
  assert.match(router, /CustomTabsIntent\.Builder\(\)[\s\S]*?\.launchUrl\(context, Uri\.parse\(url\)\)/)
  assert.match(preview, /fun startIfNeeded\(\) \{\s+if \(hasLoaded\) return/)
  for (const host of ['accounts.google.com', 'signin.google.com', 'myaccount.google.com', 'oauth2.googleapis.com']) {
    assert.match(router, new RegExp(host.replaceAll('.', '\\.')))
  }
})
