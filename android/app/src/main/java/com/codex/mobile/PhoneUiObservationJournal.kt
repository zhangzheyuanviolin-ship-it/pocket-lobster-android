package com.codex.mobile

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File
import org.json.JSONObject

/** Bounded app-private evidence. Never credentials, never an upload, never a model input. */
internal object PhoneUiObservationJournal {
    private const val MAX_BYTES = 24L * 1024 * 1024
    private const val MAX_OBSERVATIONS = 8

    fun save(context: Context, png: ByteArray, metadata: JSONObject): File? = runCatching {
        val dir = File(context.filesDir, "phone-ui-agent/observations").apply { mkdirs() }
        val name = "${System.currentTimeMillis()}-${metadata.optInt("round")}-${metadata.optInt("step")}"
        val prior = dir.listFiles().orEmpty().filter { it.extension == "png" }.sortedBy { it.name }.toMutableList()
        var size = prior.sumOf { it.length() }
        while (prior.isNotEmpty() && (prior.size >= MAX_OBSERVATIONS || size + png.size > MAX_BYTES)) {
            val old = prior.removeAt(0)
            size -= old.length()
            old.delete()
            File(dir, old.nameWithoutExtension + ".json").delete()
        }
        if (png.size > MAX_BYTES) return@runCatching null
        val image = File(dir, "$name.png")
        val sidecar = File(dir, "$name.json")
        write(image, png)
        write(sidecar, metadata.toString().toByteArray(Charsets.UTF_8))
        sidecar
    }.onFailure { Log.w("PhoneUiEvidence", "Observation journal unavailable", it) }.getOrNull()

    fun decision(sidecar: File?, raw: String) {
        if (sidecar == null) return
        runCatching {
            val value = JSONObject(sidecar.readText()).put("modelOutput", raw.take(16000))
            write(sidecar, value.toString().toByteArray(Charsets.UTF_8))
        }.onFailure { Log.w("PhoneUiEvidence", "Unable to append decision", it) }
    }

    private fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
}
