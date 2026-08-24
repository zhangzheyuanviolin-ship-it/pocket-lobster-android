package com.codex.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

class CodexForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "codex_running"
        private const val NOTIFICATION_ID = 1
        private const val HEALTH_INTERVAL_MS = 15_000L

        fun ensureStarted(context: android.content.Context) {
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
                if (!serverManager.isProxyReady()) {
                    serverManager.startProxy()
                }
                if (!serverManager.isServerReady()) {
                    val started = serverManager.startServer()
                    if (!started || !serverManager.waitForServer(90_000)) {
                        Log.w("CodexForegroundService", "Host server is not ready")
                    }
                }
            } catch (error: Throwable) {
                Log.w("CodexForegroundService", "Host health check failed: ${error.message}")
            } finally {
                healthCheckRunning = false
            }
        }.apply {
            name = "pocket-lobster-host-health"
            isDaemon = true
            start()
        }
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
