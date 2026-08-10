package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.*
import com.github.kr328.clash.service.clash.SelectionDebugLog
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.ILogObserver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.sendOverrideChanged
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel

class ClashManager(private val context: Context) : IClashManager,
    CoroutineScope by CoroutineScope(Dispatchers.IO) {
    private val store = ServiceStore(context)
    private val selectionDebug = SelectionDebugLog(store)
    private var logReceiver: ReceiveChannel<LogMessage>? = null

    override fun queryTunnelState(): TunnelState {
        return Clash.queryTunnelState()
    }

    override fun queryTrafficTotal(): Long {
        return Clash.queryTrafficTotal()
    }

    override fun queryProxyGroupNames(excludeNotSelectable: Boolean): List<String> {
        return Clash.queryGroupNames(excludeNotSelectable)
    }

    override fun queryProxyGroup(name: String, proxySort: ProxySort): ProxyGroup {
        return Clash.queryGroup(name, proxySort)
    }

    override fun queryConfiguration(): UiConfiguration {
        return Clash.queryConfiguration()
    }

    override fun queryProviders(): ProviderList {
        return ProviderList(Clash.queryProviders())
    }

    override fun queryOverride(slot: Clash.OverrideSlot): ConfigurationOverride {
        return Clash.queryOverride(slot)
    }

    override fun patchSelector(group: String, name: String): Boolean {
        val current = store.activeProfile
        val profile = selectionDebug.profile(current)
        val groupToken = selectionDebug.token(group)
        val selectedToken = selectionDebug.token(name)

        selectionDebug.event(
            name = "ui_patch_request",
            dedupeKey = "$profile|$groupToken|$selectedToken",
            fields = "profile=$profile group=$groupToken selected=$selectedToken",
        )

        val patched = try {
            Clash.patchSelector(group, name)
        } catch (e: Exception) {
            selectionDebug.summary(
                name = "ui_patch_failed",
                dedupeKey = "$profile|$groupToken",
                fields = "profile=$profile group=$groupToken selected=$selectedToken error=${selectionDebug.error(e)}",
            )
            selectionDebug.flush()
            throw e
        }

        var actual: String? = null
        var verificationError: Exception? = null
        if (patched && selectionDebug.enabled) {
            try {
                actual = Clash.querySelectorNow(group)
            } catch (e: Exception) {
                verificationError = e
            }
        }

        val patchFields = buildString {
            append("profile=$profile group=$groupToken selected=$selectedToken")
            append(" patched=$patched actual=${selectionDebug.tokenOrNone(actual)}")
            append(" verified=${patched && actual == name}")
            verificationError?.let {
                append(" verify_error=${selectionDebug.error(it)}")
            }
        }
        selectionDebug.event(
            name = "ui_patch_result",
            dedupeKey = "$profile|$groupToken|$selectedToken|$patched",
            fields = patchFields,
        )

        if (current == null) {
            selectionDebug.summary(
                name = "ui_database_skipped",
                dedupeKey = groupToken,
                fields = "profile=none group=$groupToken selected=$selectedToken reason=no_active_profile",
            )
            selectionDebug.flush()
            return patched
        }

        val databaseAction = if (patched) "upsert" else "remove"
        try {
            if (patched) {
                SelectionDao().setSelected(Selection(current, group, name))
            } else {
                SelectionDao().removeSelected(current, group)
            }

            selectionDebug.event(
                name = "ui_database_result",
                dedupeKey = "$profile|$groupToken|$selectedToken|$databaseAction",
                fields = "profile=$profile group=$groupToken selected=$selectedToken action=$databaseAction status=ok",
            )
        } catch (e: Exception) {
            selectionDebug.summary(
                name = "ui_database_failed",
                dedupeKey = "$profile|$groupToken|$databaseAction",
                fields = "profile=$profile group=$groupToken selected=$selectedToken action=$databaseAction error=${selectionDebug.error(e)}",
            )
            selectionDebug.flush()
            throw e
        }

        return patched
    }

    override fun patchOverride(slot: Clash.OverrideSlot, configuration: ConfigurationOverride) {
        Clash.patchOverride(slot, configuration)

        context.sendOverrideChanged()
    }

    override fun clearOverride(slot: Clash.OverrideSlot) {
        Clash.clearOverride(slot)
    }

    override suspend fun healthCheck(group: String) {
        return Clash.healthCheck(group).await()
    }

    override suspend fun updateProvider(type: Provider.Type, name: String) {
        return Clash.updateProvider(type, name).await()
    }

    override fun setLogObserver(observer: ILogObserver?) {
        synchronized(this) {
            logReceiver?.apply {
                cancel()

                Clash.forceGc()
            }

            if (observer != null) {
                logReceiver = Clash.subscribeLogcat().also { c ->
                    launch {
                        try {
                            while (isActive) {
                                observer.newItem(c.receive())
                            }
                        } catch (e: CancellationException) {
                            // intended behavior
                            // ignore
                        } catch (e: Exception) {
                            Log.w("UI crashed", e)
                        } finally {
                            withContext(NonCancellable) {
                                c.cancel()

                                Clash.forceGc()
                            }
                        }
                    }
                }
            }
        }
    }
}
