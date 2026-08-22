package com.codex.mobile

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.util.Log
import com.openminis.app.MinisApp
import com.openminis.app.integration.MinisRuntimeBridgeRuntime
import com.openminis.app.integration.MinisRuntimeBridgeService
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject
import rikka.shizuku.ShizukuProvider
import rikka.sui.Sui

class PocketLobsterApplication : MinisApp() {
    private var suiBackend = false

    override fun attachBaseContext(base: Context) {
        val processName = currentProcessName()
        suiBackend = runCatching { Sui.init(base.packageName) }.getOrDefault(false)
        if (!suiBackend) {
            ShizukuProvider.enableMultiProcessSupport(
                isShizukuProviderProcess(processName, base.packageName),
            )
        }
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        val processName = currentProcessName()
        BetaStartupDiagnostics.record(this, "application_start", processName)
        try {
            if (isMinisProcess(processName, packageName) && !suiBackend) {
                ShizukuProvider.requestBinderForNonProviderProcess(this)
            }
            super.onCreate()
            when {
                isShizukuProviderProcess(processName, packageName) -> initializeHostRuntime()
                isMinisProcess(processName, packageName) ->
                    MinisRuntimeBridgeRuntime.ensureStarted(this)
            }
            BetaStartupDiagnostics.record(this, "application_ready", processName)
        } catch (error: Throwable) {
            BetaStartupDiagnostics.record(this, "application_failed", processName, error)
            throw error
        }
    }

    override fun shouldInitializeMinisRuntime(): Boolean {
        return isMinisRuntimeProcess(currentProcessName(), packageName)
    }

    private fun initializeHostRuntime() {
        SharedBridgeTokenStore.ensure(this)
        ShizukuBridgeRuntime.ensureStarted(this)
        SharedRuntimeCliInstaller.ensureInstalled(this)
        runCatching {
            startService(Intent(this, MinisRuntimeBridgeService::class.java))
        }.onFailure { error ->
            Log.w("PocketLobsterApplication", "Minis bridge service start failed: ${error.message}")
        }
    }

    private fun currentProcessName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        return runCatching {
            File("/proc/self/cmdline").readText().trimEnd('\u0000')
        }.getOrDefault(packageName)
    }

    companion object {
        internal fun isMinisRuntimeProcess(processName: String, packageName: String): Boolean {
            return processName == "$packageName:minis" || processName == "$packageName:acra"
        }

        internal fun isMinisProcess(processName: String, packageName: String): Boolean =
            processName == "$packageName:minis"

        internal fun isShizukuProviderProcess(processName: String, packageName: String): Boolean =
            processName == packageName
    }
}

private object BetaStartupDiagnostics {
    private const val TAG = "BetaStartupDiagnostics"
    private const val BETA_PACKAGE = "com.codex.mobile.pocketlobster.beta"
    private const val MAX_LOG_BYTES = 2L * 1024L * 1024L

    fun record(context: Context, event: String, processName: String, error: Throwable? = null) {
        if (context.packageName != BETA_PACKAGE) return
        runCatching {
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "口袋大龙虾本地归档/诊断",
            )
            directory.mkdirs()
            val logFile = File(directory, "口袋大龙虾测试版-启动诊断.jsonl")
            if (logFile.length() > MAX_LOG_BYTES) {
                File(directory, "口袋大龙虾测试版-启动诊断-上一份.jsonl").delete()
                logFile.renameTo(File(directory, "口袋大龙虾测试版-启动诊断-上一份.jsonl"))
            }
            val payload = JSONObject()
                .put("timestampMs", System.currentTimeMillis())
                .put("event", event)
                .put("packageName", context.packageName)
                .put("versionName", installedVersionName(context))
                .put("processName", processName)
                .put("minisRuntimeProcess", processName.endsWith(":minis"))
            if (error != null) {
                payload.put("errorType", error.javaClass.name)
                payload.put("errorMessage", error.message ?: "")
                payload.put("stackTrace", error.stackTraceToString())
            }
            FileOutputStream(logFile, true).bufferedWriter().use { writer ->
                writer.append(payload.toString()).append('\n')
            }
        }.onFailure { failure ->
            Log.w(TAG, "shared startup diagnostic unavailable: ${failure.message}")
        }
    }

    private fun installedVersionName(context: Context): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        }.getOrDefault("unknown")
    }
}
