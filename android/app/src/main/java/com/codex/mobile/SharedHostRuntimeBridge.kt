package com.codex.mobile

import android.content.Context
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import org.json.JSONObject

object SharedBridgeTokenStore {
    private const val TOKEN_BYTES = 32

    fun tokenFile(context: Context): File =
        File(context.filesDir, "shared-runtime/bridge-token")

    @Synchronized
    fun ensure(context: Context): String {
        val file = tokenFile(context)
        val existing = runCatching { file.readText().trim() }.getOrDefault("")
        if (existing.length == TOKEN_BYTES * 2) return existing

        file.parentFile?.mkdirs()
        val bytes = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        file.writeText(token)
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
        return token
    }

    fun matches(context: Context, candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        val expected = runCatching { ensure(context) }.getOrDefault("")
        if (candidate.length != expected.length) return false
        var difference = 0
        candidate.indices.forEach { index ->
            difference = difference or (candidate[index].code xor expected[index].code)
        }
        return difference == 0
    }
}

data class SharedRuntimeExecResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
)

object SharedHostRuntimeBridge {
    private const val MAX_TIMEOUT_SECONDS = 900L

    fun status(context: Context): JSONObject {
        val paths = BootstrapInstaller.getPaths(context)
        val ubuntuBridge = File(
            paths.homeDir,
            ".openclaw-android/linux-runtime/bin/ubuntu-shell.sh",
        )
        return JSONObject()
            .put("ok", true)
            .put("local", File(paths.prefixDir, "bin/sh").exists())
            .put("ubuntu", ubuntuBridge.exists() && ubuntuBridge.canExecute())
            .put("bridge", "host")
    }

    fun execute(
        context: Context,
        runtime: String,
        command: String,
        timeoutSeconds: Long,
    ): SharedRuntimeExecResult {
        if (command.isBlank()) {
            return SharedRuntimeExecResult(2, "command is required", false)
        }
        val timeout = timeoutSeconds.coerceIn(1L, MAX_TIMEOUT_SECONDS)
        val manager = CodexServerManager(context.applicationContext)
        val process = when (runtime) {
            "local" -> manager.startPrefixProcess(command)
            "ubuntu" -> manager.startUbuntuProcess(command)
            else -> return SharedRuntimeExecResult(2, "unsupported runtime: $runtime", false)
        }

        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) { output.append(line).append('\n') }
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val finished = runCatching {
            process.waitFor(timeout, TimeUnit.SECONDS)
        }.getOrDefault(false)
        if (!finished) {
            process.destroy()
            if (!runCatching { process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) {
                process.destroyForcibly()
            }
        }
        runCatching { reader.join(2_000L) }
        val exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(1) else 124
        return SharedRuntimeExecResult(
            exitCode = exitCode,
            output = synchronized(output) { output.toString().trimEnd() },
            timedOut = !finished,
        )
    }
}

object SharedRuntimeCliInstaller {
    private val assetFiles = listOf(
        "shared-runtime/shared-runtime-cli.js" to "libexec/pocketlobster/shared-runtime-cli.js",
        "shared-runtime/alpine-shell" to "bin/alpine-shell",
        "shared-runtime/minis-browser" to "bin/minis-browser",
    )

    fun ensureInstalled(context: Context) {
        SharedBridgeTokenStore.ensure(context)
        val paths = BootstrapInstaller.getPaths(context)
        if (!File(paths.prefixDir, "bin").isDirectory) return
        assetFiles.forEach { (asset, relativeTarget) ->
            val target = File(paths.prefixDir, relativeTarget)
            target.parentFile?.mkdirs()
            val bytes = context.assets.open(asset).use { it.readBytes() }
            if (!target.exists() || !target.readBytes().contentEquals(bytes)) {
                target.writeBytes(bytes)
            }
            target.setExecutable(true, true)
        }
    }
}
