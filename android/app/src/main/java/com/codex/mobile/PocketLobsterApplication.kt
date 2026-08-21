package com.codex.mobile

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import com.openminis.app.MinisApp
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

class PocketLobsterApplication : MinisApp() {
    override fun onCreate() {
        val processName = currentProcessName()
        BetaStartupDiagnostics.record(this, "application_start", processName)
        try {
            super.onCreate()
            BetaStartupDiagnostics.record(this, "application_ready", processName)
        } catch (error: Throwable) {
            BetaStartupDiagnostics.record(this, "application_failed", processName, error)
            throw error
        }
    }

    override fun shouldInitializeMinisRuntime(): Boolean {
        return isMinisRuntimeProcess(currentProcessName(), packageName)
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
