package com.openminis.app.integration

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.sandbox.ExecutionCoordinator
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

object SharedMinisRuntime {
    private const val SHARED_SESSION_ID = "pocket-lobster-shared"
    @Volatile private var browserPool: BrowserTabPool? = null

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

    suspend fun executeAlpine(command: String, timeoutMs: Long) =
        ExecutionCoordinator.execute(
            sessionId = SHARED_SESSION_ID,
            command = command,
            timeout = timeoutMs,
        )
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

    override fun serve(session: IHTTPSession): Response {
        if (!SharedBridgeToken.matches(context, session.headers["x-pocket-lobster-token"])) {
            return json(Response.Status.UNAUTHORIZED, JSONObject().put("ok", false).put("error", "unauthorized"))
        }
        return try {
            when {
                session.method == Method.GET && session.uri == "/status" -> status()
                session.method == Method.POST && session.uri == "/alpine/exec" -> alpine(session)
                session.method == Method.POST && session.uri == "/browser/call" -> browser(session)
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
        val command = payload.optString("command", "")
        if (command.isBlank()) {
            return json(Response.Status.BAD_REQUEST, JSONObject().put("ok", false).put("error", "command_required"))
        }
        val timeoutMs = payload.optLong("timeout", 900L).coerceIn(1L, 900L) * 1_000L
        val result = runBlocking { SharedMinisRuntime.executeAlpine(command, timeoutMs) }
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", result.exitCode == 0)
                .put("exitCode", result.exitCode)
                .put("output", result.output),
        )
    }

    private fun browser(session: IHTTPSession): Response {
        val payload = body(session)
        val input = BrowserActionInput.parse(payload.toString())
            ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("ok", false).put("error", "invalid_browser_action"))
        val result = runBlocking { SharedMinisRuntime.browser(context).execute(input, singleTab = true) }
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", result.success)
                .put("output", result.text)
                .put("pageURL", result.pageURL ?: JSONObject.NULL)
                .put("tabId", result.tabId ?: JSONObject.NULL)
                .put("imageFilePath", result.imageFilePath ?: JSONObject.NULL)
                .put("fetchedFileName", result.fetchedFileName ?: JSONObject.NULL),
        )
    }

    private fun body(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"]?.takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()
    }

    private fun json(status: Response.Status, payload: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", payload.toString())
}
