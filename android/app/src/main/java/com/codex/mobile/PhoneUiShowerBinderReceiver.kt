package com.codex.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ai.assistance.shower.IShowerService
import com.ai.assistance.shower.ShowerBinderContainer
import com.ai.assistance.showerclient.ShowerBinderRegistry

class PhoneUiShowerBinderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SHOWER_BINDER_READY) return
        @Suppress("DEPRECATION")
        val container = intent.getParcelableExtra<ShowerBinderContainer>(EXTRA_BINDER_CONTAINER)
        val binder = container?.binder
        val validDescriptor = runCatching { binder?.interfaceDescriptor }.getOrNull() == SHOWER_DESCRIPTOR
        val service = binder?.takeIf { validDescriptor }?.let { IShowerService.Stub.asInterface(it) }
        ShowerBinderRegistry.setService(service?.takeIf { it.asBinder().isBinderAlive })
    }

    companion object {
        const val ACTION_SHOWER_BINDER_READY = "com.ai.assistance.operit.action.SHOWER_BINDER_READY"
        private const val EXTRA_BINDER_CONTAINER = "binder_container"
        private const val SHOWER_DESCRIPTOR = "com.ai.assistance.shower.IShowerService"
    }
}
