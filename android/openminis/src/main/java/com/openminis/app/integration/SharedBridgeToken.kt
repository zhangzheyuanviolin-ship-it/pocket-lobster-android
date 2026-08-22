package com.openminis.app.integration

import android.content.Context
import java.io.File

internal object SharedBridgeToken {
    fun read(context: Context): String = runCatching {
        File(context.filesDir, "shared-runtime/bridge-token").readText().trim()
    }.getOrDefault("")

    fun matches(context: Context, candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        val expected = read(context)
        if (candidate.length != expected.length || expected.isEmpty()) return false
        var difference = 0
        candidate.indices.forEach { index ->
            difference = difference or (candidate[index].code xor expected[index].code)
        }
        return difference == 0
    }
}
