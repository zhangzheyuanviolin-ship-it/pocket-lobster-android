package com.codex.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.openminis.app.MainActivity as OpenMinisActivity
import com.openminis.app.integration.SharedBrowserActivity

object MinisLauncher {
    fun openHome(context: Context) {
        open(context, null)
    }

    fun openProviders(context: Context) {
        open(context, Uri.parse("minis://settings/providers"))
    }

    fun openNewChat(context: Context) {
        open(context, Uri.parse("minis://action/new_chat"))
    }

    fun openBrowser(context: Context) {
        context.startActivity(
            Intent(context, SharedBrowserActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            },
        )
    }

    private fun open(context: Context, destination: Uri?) {
        context.startActivity(
            Intent(context, OpenMinisActivity::class.java).apply {
                data = destination
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            },
        )
    }
}
