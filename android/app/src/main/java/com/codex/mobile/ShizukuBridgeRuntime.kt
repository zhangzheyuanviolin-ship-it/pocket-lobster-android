package com.codex.mobile

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.net.InetSocketAddress
import java.net.Socket
import com.openminis.app.integration.PocketLobsterPortPolicy

object ShizukuBridgeRuntime {
    private const val TAG = "ShizukuBridgeRuntime"

    @Volatile
    private var server: ShizukuShellBridgeServer? = null

    fun port(context: Context): Int = PocketLobsterPortPolicy.hostBridgePort(context.packageName)

    @Synchronized
    fun ensureStarted(context: Context): Boolean {
        if (isBridgeReachable(context)) return true
        return try {
            val bridgePort = port(context)
            val newServer = ShizukuShellBridgeServer(context.applicationContext, bridgePort)
            newServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = newServer
            Log.i(TAG, "Bridge server started on $bridgePort")
            true
        } catch (error: Exception) {
            if (isBridgeReachable(context)) {
                Log.i(TAG, "Bridge already running on ${port(context)}")
                true
            } else {
                Log.w(TAG, "Failed to start bridge server: ${error.message}")
                false
            }
        }
    }

    fun isBridgeReachable(context: Context, timeoutMs: Int = 350): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress("127.0.0.1", port(context)),
                    timeoutMs,
                )
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
