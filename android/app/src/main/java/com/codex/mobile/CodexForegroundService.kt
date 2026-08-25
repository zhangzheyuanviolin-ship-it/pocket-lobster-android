package com.codex.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

class CodexForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "codex_running"
        private const val NOTIFICATION_ID = 1
        private const val HEALTH_INTERVAL_MS = 15_000L

        fun ensureStarted(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, CodexForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }
    }

    private lateinit var serverManager: CodexServerManager
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var healthCheckRunning = false
    @Volatile private var lastReportedState = ""
    private val healthMonitor = object : Runnable {
        override fun run() {
            ensureHostServer()
            handler.postDelayed(this, HEALTH_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        serverManager = CodexServerManager(applicationContext)
        CollaborationHostDiagnostics.record(this, "service_created")
        handler.post(healthMonitor)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureHostServer()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(healthMonitor)
        super.onDestroy()
    }

    private fun ensureHostServer() {
        if (healthCheckRunning) return
        healthCheckRunning = true
        Thread {
            try {
                if (!BootstrapInstaller.isBootstrapInstalled(this)) {
                    reportState("bootstrap_missing")
                    return@Thread
                }
                val bundleReady = serverManager.installServerBundle { progress ->
                    Log.i("CodexForegroundService", progress)
                }
                if (!bundleReady) {
                    reportState("bundle_install_failed")
                    return@Thread
                }
                if (!serverManager.isProxyReady()) {
                    if (!serverManager.startProxy()) {
                        reportState("proxy_start_failed")
                        return@Thread
                    }
                }
                if (!serverManager.isServerReady()) {
                    val started = serverManager.startServer()
                    if (!started) {
                        reportState("server_start_failed")
                        return@Thread
                    }
                    if (!serverManager.waitForServer(90_000)) {
                        Log.w("CodexForegroundService", "Host server is not ready")
                        reportState("server_health_timeout", serverManager.describeServerHealth())
                        return@Thread
                    }
                }
                reportState("ready")
            } catch (error: Throwable) {
                Log.w("CodexForegroundService", "Host health check failed: ${error.message}")
                reportState("health_check_exception", error.message.orEmpty())
            } finally {
                healthCheckRunning = false
            }
        }.apply {
            name = "pocket-lobster-host-health"
            isDaemon = true
            start()
        }
    }

    private fun reportState(state: String, detail: String = "") {
        val key = "$state:$detail"
        if (lastReportedState == key) return
        lastReportedState = key
        CollaborationHostDiagnostics.record(this, state, detail)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AnyClaw Running",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps Codex server running in the background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("AnyClaw is running")
            .setContentText("Server active in background")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}

internal object CollaborationHostDiagnostics {
    private const val MAX_BYTES = 2L * 1024L * 1024L

    @Synchronized
    fun record(context: Context, event: String, detail: String = "") {
        if (!context.packageName.endsWith(".beta")) return
        runCatching {
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "口袋大龙虾本地归档/诊断",
            ).apply { mkdirs() }
            val target = File(directory, "三智能体协作宿主诊断.jsonl")
            if (target.length() > MAX_BYTES) {
                val previous = File(directory, "三智能体协作宿主诊断-上一份.jsonl")
                previous.delete()
                target.renameTo(previous)
            }
            val versionName = runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            }.getOrDefault("")
            val payload = JSONObject()
                .put("timestampMs", System.currentTimeMillis())
                .put("event", event)
                .put("detail", detail)
                .put("packageName", context.packageName)
                .put("versionName", versionName)
                .put("processName", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) android.app.Application.getProcessName() else context.packageName)
            FileOutputStream(target, true).bufferedWriter().use { writer ->
                writer.append(payload.toString()).append('\n')
            }
        }.onFailure { error ->
            Log.w("CollaborationHostDiagnostics", "Shared diagnostic failed: ${error.message}")
        }
    }
}
