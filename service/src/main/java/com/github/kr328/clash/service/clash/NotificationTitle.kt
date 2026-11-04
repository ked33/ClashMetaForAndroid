package com.github.kr328.clash.service.clash

import android.content.Context
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.StatusProvider
import kotlinx.coroutines.channels.Channel

internal class NotificationTitle(private val context: Context) {
    private var refreshRoute = true

    fun profileChanged() {
        refreshRoute = true
    }

    fun resolve(): String {
        if (StatusProvider.currentProfile == null) {
            refreshRoute = true
            return context.getString(R.string.loading)
        }
        val proxy = Clash.queryNotificationProxy(refreshRoute)
        refreshRoute = false
        return when {
            proxy.name.isEmpty() -> context.getString(R.string.running)
            proxy.type == "LoadBalance" -> context.getString(R.string.notification_load_balance, proxy.name)
            else -> proxy.name
        }
    }

    companion object {
        // Only one notification module runs. Coalesce selection changes without
        // Android broadcasts, keeping neither Context nor UI objects in the signal.
        val changes = Channel<Unit>(Channel.CONFLATED)

        fun selectionChanged() {
            changes.trySend(Unit)
        }
    }
}
