package com.openminis.app.integration

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.util.Base64
import android.webkit.CookieManager
import com.openminis.app.browser.BrowserAction
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserActionResult
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.debug.DebugRPCHandler
import com.openminis.app.sandbox.ExecutionCoordinator
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SharedMinisRuntime {
    private const val SHARED_SESSION_ID = "pocket-lobster-shared"
    @Volatile private var browserPool: BrowserTabPool? = null
    private val browserTabsByAgent = ConcurrentHashMap<String, Int>()
    private val browserAllocationLock = Mutex()

    fun registerBrowser(pool: BrowserTabPool) {
        browserPool = pool
    }

    @Synchronized
    fun browser(context: Context): BrowserTabPool {
        browserPool?.let { return it }
        return BrowserTabPool(context.applicationContext).also {
            it.setSession(SHARED_SESSION_ID)
            browserPool = it
        }
    }

    suspend fun executeAlpine(agentId: String, command: String, timeoutMs: Long) =
        ExecutionCoordinator.execute(
            sessionId = "$SHARED_SESSION_ID-${normalizeAgentId(agentId)}",
            command = command,
            timeout = timeoutMs,
        )

    suspend fun executeBrowser(
        context: Context,
        agentId: String,
        input: BrowserActionInput,
    ): BrowserActionResult {
        val pool = browser(context)
        val normalizedAgentId = normalizeAgentId(agentId)
        val normalizedInput = normalizeBrowserInput(input)
        if (normalizedInput.action == BrowserAction.LIST_TABS) {
            return pool.execute(normalizedInput, singleTab = false)
        }
        if (normalizedInput.action == BrowserAction.NEW_TAB) {
            val result = pool.execute(normalizedInput, singleTab = false)
            result.tabId?.let { browserTabsByAgent[normalizedAgentId] = it }
            return result
        }
        if (normalizedInput.tabId != null && pool.tabs.value.none { it.id == normalizedInput.tabId }) {
            return BrowserActionResult.error("Tab ${normalizedInput.tabId} not found; call list_tabs and retry with an existing tab_id")
        }

        val targetTab = browserAllocationLock.withLock {
            // An explicit tab ID is a deliberate handoff. BrowserTabPool serializes
            // concurrent actions on that tab, so every agent may safely take it over.
            val requestedTab = normalizedInput.tabId
            val mappedTab = browserTabsByAgent[normalizedAgentId]
                ?.takeIf { id -> pool.tabs.value.any { it.id == id } }
            requestedTab ?: mappedTab ?: allocateBrowserTab(pool, normalizedAgentId)
        }
        if (targetTab == null) {
            return BrowserActionResult.error("No browser tab is available for agent $normalizedAgentId")
        }
        browserTabsByAgent[normalizedAgentId] = targetTab
        val routedInput = normalizedInput.copy(tabId = targetTab)
        var result = pool.execute(routedInput, singleTab = false)
        if (!result.success && routedInput.action == BrowserAction.FETCH && !routedInput.url.isNullOrBlank()) {
            result = nativeFetch(routedInput.url, targetTab)
        }
        if (normalizedInput.action == BrowserAction.CLOSE_TAB && result.success) {
            browserTabsByAgent.remove(normalizedAgentId, targetTab)
        }
        return result
    }

    private fun normalizeBrowserInput(input: BrowserActionInput): BrowserActionInput {
        if (input.action == BrowserAction.EXECUTE_JS) {
            val script = input.script?.trim().orEmpty()
            val expression = script.trimEnd(';').trim()
            val isBareExpression = expression.isNotEmpty() &&
                !expression.startsWith("return ") &&
                !expression.contains(';') &&
                !Regex("""(^|\s)(var|let|const|if|for|while|try|function|class)\b""").containsMatchIn(expression)
            return if (isBareExpression) input.copy(script = "return ($expression)") else input
        }

        val selectorType = input.selectorType?.trim()?.lowercase().orEmpty()
        val rawSelector = input.selector?.trim().orEmpty()
        if (selectorType.isBlank() || selectorType == "css" || rawSelector.isBlank()) return input
        if (input.action !in setOf(
                BrowserAction.CLICK,
                BrowserAction.TYPE,
                BrowserAction.GET_TEXT,
                BrowserAction.FIND_ELEMENTS,
                BrowserAction.HOVER,
            )
        ) return input

        val selectorValue = if (selectorType == "text" && rawSelector.startsWith("text=", ignoreCase = true)) {
            rawSelector.substringAfter('=').trim()
        } else {
            rawSelector
        }
        val selector = JSONObject.quote(selectorValue)
        val allLocator = when (selectorType) {
            "xpath" -> "Array.from((function(){var s=document.evaluate($selector,document,null,XPathResult.ORDERED_NODE_SNAPSHOT_TYPE,null),a=[];for(var i=0;i<s.snapshotLength;i++)a.push(s.snapshotItem(i));return a;})())"
            "text" -> "Array.from(document.querySelectorAll('button,a,input,textarea,select,[role=button],[role=link],[contenteditable=true]')).filter(function(node){var wanted=$selector.toLowerCase();var actual=(node.innerText||node.value||node.getAttribute('aria-label')||node.getAttribute('placeholder')||'').trim().toLowerCase();return actual===wanted||actual.indexOf(wanted)>=0;})"
            else -> return input
        }
        val script = when (input.action) {
            BrowserAction.CLICK -> "var el=$allLocator[0]; if(!el) throw new Error('Element not found: '+$selector); el.scrollIntoView({block:'center'}); el.click(); return {clicked:true,selectorType:${JSONObject.quote(selectorType)}};"
            BrowserAction.TYPE -> {
                val value = JSONObject.quote(input.text ?: "")
                "var el=$allLocator[0]; if(!el) throw new Error('Element not found: '+$selector); el.focus(); var value=$value; var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype; var setter=Object.getOwnPropertyDescriptor(proto,'value'); if(setter&&setter.set){setter.set.call(el,value);}else{el.value=value;} el.dispatchEvent(new InputEvent('input',{data:value,inputType:'insertText',bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true})); return {typed:true,length:value.length,selectorType:${JSONObject.quote(selectorType)}};"
            }
            BrowserAction.GET_TEXT -> "var el=$allLocator[0]; if(!el) throw new Error('Element not found: '+$selector); return (el.innerText||el.textContent||el.value||'').trim();"
            BrowserAction.HOVER -> "var el=$allLocator[0]; if(!el) throw new Error('Element not found: '+$selector); el.dispatchEvent(new MouseEvent('mouseover',{bubbles:true})); return {hovered:true,selectorType:${JSONObject.quote(selectorType)}};"
            BrowserAction.FIND_ELEMENTS -> "return $allLocator.slice(0,100).map(function(el,index){return {index:index,tag:(el.tagName||'').toLowerCase(),text:(el.innerText||el.textContent||el.value||'').trim().slice(0,240),id:el.id||'',className:typeof el.className==='string'?el.className:'',href:el.href||'',ariaLabel:el.getAttribute&&el.getAttribute('aria-label')||''};});"
            else -> return input
        }
        return BrowserActionInput(
            action = BrowserAction.EXECUTE_JS,
            script = script,
            tabId = input.tabId,
        )
    }

    private suspend fun nativeFetch(url: String, tabId: Int): BrowserActionResult = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/136 Mobile Safari/537.36")
            CookieManager.getInstance().getCookie(url)?.takeIf(String::isNotBlank)?.let {
                connection.setRequestProperty("Cookie", it)
            }
            val status = connection.responseCode
            val contentType = connection.contentType.orEmpty()
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val data = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var remaining = 32 * 1024 * 1024
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
                output.toByteArray()
            } ?: ByteArray(0)
            if (status !in 200..299) {
                return@withContext BrowserActionResult.error("Fetch failed with HTTP $status").copy(tabId = tabId)
            }
            val extension = contentType.substringBefore(';').substringAfterLast('/').ifBlank { "bin" }
            BrowserActionResult(
                text = "Fetched ${connection.url}\n  Status: $status\n  Content-Type: $contentType\n  Size: ${data.size} bytes\n  Transport: native browser-session fallback",
                fetchedFileData = data,
                fetchedFileName = "fetch_${System.currentTimeMillis()}.$extension",
                pageURL = connection.url.toString(),
                tabId = tabId,
            )
        } catch (error: Throwable) {
            BrowserActionResult.error("Fetch failed: ${error.message ?: error.javaClass.name}").copy(tabId = tabId)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun allocateBrowserTab(pool: BrowserTabPool, agentId: String): Int? {
        val claimed = browserTabsByAgent.values.toSet()
        pool.tabs.value.firstOrNull { it.id !in claimed }?.let {
            browserTabsByAgent[agentId] = it.id
            return it.id
        }
        val created = pool.execute(BrowserActionInput(action = BrowserAction.NEW_TAB), singleTab = false)
        created.tabId?.let { browserTabsByAgent[agentId] = it }
        return created.tabId
    }

    private fun normalizeAgentId(value: String): String {
        val normalized = value.trim().lowercase()
        return when (normalized) {
            "claude", "claude-code" -> "claude"
            "minis", "openminis" -> "minis"
            else -> "codex"
        }
    }
}

object MinisRuntimeBridgeRuntime {
    const val PORT = 18927
    @Volatile private var server: MinisRuntimeBridgeServer? = null

    @Synchronized
    fun ensureStarted(context: Context): Boolean {
        server?.let { if (it.wasStarted()) return true }
        return runCatching {
            MinisRuntimeBridgeServer(context.applicationContext).also {
                it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = it
            }
            true
        }.getOrDefault(false)
    }
}

class MinisRuntimeBridgeService : Service() {
    override fun onCreate() {
        super.onCreate()
        MinisRuntimeBridgeRuntime.ensureStarted(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MinisRuntimeBridgeRuntime.ensureStarted(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

private class MinisRuntimeBridgeServer(
    private val context: Context,
) : NanoHTTPD("127.0.0.1", MinisRuntimeBridgeRuntime.PORT) {
    private val chatRpcHandler = DebugRPCHandler(context)

    override fun serve(session: IHTTPSession): Response {
        if (!SharedBridgeToken.matches(context, session.headers["x-pocket-lobster-token"])) {
            return json(Response.Status.UNAUTHORIZED, JSONObject().put("ok", false).put("error", "unauthorized"))
        }
        return try {
            when {
                session.method == Method.GET && session.uri == "/status" -> status()
                session.method == Method.GET && session.uri == "/browser/schema" -> browserSchema()
                session.method == Method.POST && session.uri == "/alpine/exec" -> alpine(session)
                session.method == Method.POST && session.uri == "/browser/call" -> browser(session)
                session.method == Method.POST && session.uri == "/chat/rpc" -> chatRpc(session)
                else -> json(Response.Status.NOT_FOUND, JSONObject().put("ok", false).put("error", "not_found"))
            }
        } catch (error: Throwable) {
            json(
                Response.Status.INTERNAL_ERROR,
                JSONObject().put("ok", false).put("error", error.message ?: error.javaClass.name),
            )
        }
    }

    private fun status(): Response {
        val pool = SharedMinisRuntime.browser(context)
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", true)
                .put("bridge", "minis")
                .put("browserTabs", pool.tabs.value.size)
                .put("alpine", true),
        )
    }

    private fun alpine(session: IHTTPSession): Response {
        val payload = body(session)
        val agentId = payload.optString("agent_id", "codex")
        val command = payload.optString("command", "")
        if (command.isBlank()) {
            return json(Response.Status.BAD_REQUEST, JSONObject().put("ok", false).put("error", "command_required"))
        }
        val timeoutMs = payload.optLong("timeout", 900L).coerceIn(1L, 900L) * 1_000L
        val result = runBlocking { SharedMinisRuntime.executeAlpine(agentId, command, timeoutMs) }
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", result.exitCode == 0)
                .put("exitCode", result.exitCode)
                .put("output", result.output),
        )
    }

    private fun browser(session: IHTTPSession): Response {
        val payload = normalizeBrowserPayload(body(session))
        val agentId = payload.optString("agent_id", "codex")
        val input = BrowserActionInput.parse(payload.toString())
            ?: return json(
                Response.Status.BAD_REQUEST,
                browserSchemaPayload()
                    .put("ok", false)
                    .put("error", "invalid_browser_action")
                    .put("detail", "Run minis-browser --help or minis-browser schema for actions and parameters."),
            )
        val result = runBlocking { executeBrowserAction(agentId, input, payload) }
        val image = prepareBridgeImage(result, payload)
        val fetchedFilePath = prepareFetchedFile(result)
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", result.success)
                .put("output", result.text)
                .put("pageURL", result.pageURL ?: JSONObject.NULL)
                .put("tabId", result.tabId ?: JSONObject.NULL)
                .put("imageFilePath", image.path ?: JSONObject.NULL)
                .put("imageMimeType", image.mimeType ?: JSONObject.NULL)
                .put("imageBase64", image.base64 ?: JSONObject.NULL)
                .put("imageExportError", image.error ?: JSONObject.NULL)
                .put("fetchedFileName", result.fetchedFileName ?: JSONObject.NULL)
                .put("fetchedFilePath", fetchedFilePath ?: JSONObject.NULL),
        )
    }

    private fun chatRpc(session: IHTTPSession): Response {
        val request = body(session).toString()
        val response = runBlocking { chatRpcHandler.handle(request) }
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", response)
    }

    private suspend fun executeBrowserAction(
        agentId: String,
        parsedInput: BrowserActionInput,
        payload: JSONObject,
    ): BrowserActionResult {
        val input = parsedInput
        val selectorAction = parsedInput.action in setOf(
            BrowserAction.CLICK,
            BrowserAction.TYPE,
            BrowserAction.GET_TEXT,
            BrowserAction.FIND_ELEMENTS,
            BrowserAction.HOVER,
        ) && !parsedInput.selector.isNullOrBlank()

        if (selectorAction) {
            SharedMinisRuntime.executeBrowser(
                context,
                agentId,
                BrowserActionInput(action = BrowserAction.WAIT_FOR_DOM_STABLE, timeoutMs = 2_500),
            )
        }

        var result = SharedMinisRuntime.executeBrowser(context, agentId, input)
        if (selectorAction) {
            repeat(3) {
                if (result.success || !result.text.contains("Element not found", ignoreCase = true)) {
                    return result
                }
                delay(350)
                result = SharedMinisRuntime.executeBrowser(context, agentId, input)
            }
            if (!result.success) {
                val contextResult = SharedMinisRuntime.executeBrowser(
                    context,
                    agentId,
                    BrowserActionInput(action = BrowserAction.GET_PAGE_INFO),
                )
                result = result.copy(text = "${result.text}\nPage context after retries:\n${contextResult.text}")
            }
        }
        return result
    }

    private fun normalizeBrowserPayload(payload: JSONObject): JSONObject {
        if (payload.optString("action") == "scroll_and_collect" &&
            payload.optString("item_selector").isBlank() &&
            payload.optString("selector").isNotBlank()
        ) {
            payload.put("item_selector", payload.optString("selector"))
        }
        if (payload.optString("selector_type").isBlank()) {
            val selector = payload.optString("selector").trim()
            when {
                selector.startsWith("text=", ignoreCase = true) -> payload.put("selector_type", "text")
                selector.startsWith("//") || selector.startsWith("(") -> payload.put("selector_type", "xpath")
            }
        }
        return payload
    }

    private data class BridgeImage(
        val path: String? = null,
        val mimeType: String? = null,
        val base64: String? = null,
        val error: String? = null,
    )

    private fun prepareBridgeImage(result: BrowserActionResult, payload: JSONObject): BridgeImage {
        val sourcePath = result.imageFilePath?.takeIf(String::isNotBlank) ?: return BridgeImage()
        val bitmap = BitmapFactory.decodeFile(sourcePath)
            ?: return BridgeImage(path = sourcePath, error = "screenshot_decode_failed")
        val maxDimension = 1_800
        val preview = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            val scale = minOf(maxDimension.toFloat() / bitmap.width, maxDimension.toFloat() / bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        return try {
            val requested = payload.optString("output_path", payload.optString("output", "")).trim()
            val destination = resolvePngOutput(requested)
            destination.parentFile?.mkdirs()
            destination.outputStream().use { output ->
                check(preview.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            val encoded = if (payload.optBoolean("include_base64", false)) {
                Base64.encodeToString(destination.readBytes(), Base64.NO_WRAP)
            } else {
                null
            }
            BridgeImage(
                path = destination.absolutePath,
                mimeType = "image/png",
                base64 = encoded,
            )
        } catch (error: Throwable) {
            BridgeImage(path = sourcePath, error = error.message ?: error.javaClass.name)
        } finally {
            if (preview !== bitmap) preview.recycle()
            bitmap.recycle()
        }
    }

    private fun resolvePngOutput(requested: String): File {
        if (requested.isBlank()) {
            return File(context.cacheDir, "browser_bridge_screenshots/screenshot_${System.currentTimeMillis()}.png")
        }
        val candidate = File(requested).let { file ->
            val path = file.absolutePath
            if (path.endsWith(".png", ignoreCase = true)) file else File("$path.png")
        }.canonicalFile
        val allowedRoots = listOfNotNull(
            context.dataDir,
            context.getExternalFilesDir(null),
            File("/storage/emulated/0"),
            File("/sdcard"),
        ).mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        check(allowedRoots.any { root -> candidate.path == root.path || candidate.path.startsWith("${root.path}${File.separator}") }) {
            "output_path_not_allowed"
        }
        return candidate
    }

    private fun prepareFetchedFile(result: BrowserActionResult): String? {
        val data = result.fetchedFileData ?: return null
        val safeName = result.fetchedFileName
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.takeIf(String::isNotBlank)
            ?: "fetch_${System.currentTimeMillis()}.bin"
        return runCatching {
            val output = File(context.cacheDir, "browser_bridge_fetches/$safeName")
            output.parentFile?.mkdirs()
            output.writeBytes(data)
            output.absolutePath
        }.getOrNull()
    }

    private fun browserSchema(): Response = json(Response.Status.OK, browserSchemaPayload())

    private fun browserSchemaPayload(): JSONObject = JSONObject()
        .put("ok", true)
        .put("tool", "minis-browser")
        .put("actions", JSONArray(BROWSER_ACTIONS))
        .put("selectorTypes", JSONArray(listOf("css", "xpath", "text")))
        .put(
            "inputSchema",
            JSONObject()
                .put("required", JSONArray(listOf("action")))
                .put("selectorTypeDefault", "css")
                .put("timeoutUnit", "seconds")
                .put("screenshotOutputPath", "absolute PNG path")
                .put("includeBase64", "optional boolean"),
        )
        .put("screenshotMimeType", "image/png")
        .put("help", "Run minis-browser --help for parameters and examples.")

    private fun body(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"]?.takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()
    }

    private fun json(status: Response.Status, payload: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", payload.toString())

    companion object {
        private val BROWSER_ACTIONS = listOf(
            "navigate", "back", "forward", "reload", "screenshot", "click", "type",
            "get_text", "scroll", "get_page_info", "execute_js", "find_elements", "hover",
            "get_readable", "set_user_agent", "set_viewport", "get_backbone", "fetch",
            "new_tab", "close_tab", "list_tabs", "get_cookies", "set_cookies",
            "scroll_and_collect", "wait_for_dom_stable",
        )
    }
}
