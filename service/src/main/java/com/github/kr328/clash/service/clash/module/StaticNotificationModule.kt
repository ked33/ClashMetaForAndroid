package com.github.kr328.clash.service.clash.module

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.compat.startForegroundCompat
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.clash.NotificationTitle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import java.util.concurrent.TimeUnit

class StaticNotificationModule(service: Service) : Module<Unit>(service) {
    private val builder = NotificationCompat.Builder(service, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_logo_service)
        .setOngoing(true)
        .setColor(service.getColorCompat(R.color.color_clash))
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(
            PendingIntent.getActivity(
                service,
                R.id.nf_clash_status,
                Intent().setComponent(Components.MAIN_ACTIVITY)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
            )
        )

    override suspend fun run() = coroutineScope {
        val title = NotificationTitle(service)
        var previousTitle: String? = null
        var previousReady = false
        var interactive = service.getSystemService<PowerManager>()?.isInteractive ?: true

        fun update() {
            val currentTitle = title.resolve()
            val ready = StatusProvider.currentProfile != null
            if (currentTitle == previousTitle && ready == previousReady) return

            val notification = builder
                .setContentTitle(currentTitle)
                .setContentText(service.getText(if (ready) R.string.running else R.string.loading))
                .build()
            service.startForegroundCompat(R.id.nf_clash_status, notification)
            previousTitle = currentTitle
            previousReady = ready
        }

        val loaded = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_PROFILE_LOADING)
            addAction(Intents.ACTION_PROFILE_LOADED)
        }
        val screenToggle = receiveBroadcast(false, Channel.CONFLATED) {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        update()

        while (true) {
            select<Unit> {
                loaded.onReceive {
                    title.profileChanged()
                    update()
                }
                screenToggle.onReceive {
                    interactive = it.action == Intent.ACTION_SCREEN_ON
                    if (interactive) update()
                }
                NotificationTitle.changes.onReceive {
                    update()
                }
                if (interactive) {
                    ticker.onReceive { update() }
                }
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "clash_status_channel"

        fun createNotificationChannel(service: Service) {
            NotificationManagerCompat.from(service).createNotificationChannel(
                NotificationChannelCompat.Builder(
                    CHANNEL_ID,
                    NotificationManagerCompat.IMPORTANCE_LOW
                ).setName(service.getText(R.string.clash_service_status_channel)).build()
            )
        }

        fun notifyLoadingNotification(service: Service) {
            val notification =
                NotificationCompat.Builder(service, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_logo_service)
                    .setOngoing(true)
                    .setColor(service.getColorCompat(R.color.color_clash))
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setContentTitle(service.getText(R.string.loading))
                    .build()

            service.startForegroundCompat(R.id.nf_clash_status, notification)
        }
    }
}