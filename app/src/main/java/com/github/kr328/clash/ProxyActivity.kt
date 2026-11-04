package com.github.kr328.clash

import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyDelay
import com.github.kr328.clash.design.ProxyDesign
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.service.remote.IUrlTestObserver
import com.github.kr328.clash.util.ProxyDelayUpdates
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class ProxyActivity : BaseActivity<ProxyDesign>() {
    override suspend fun main() {
        val ready = withContext(Dispatchers.IO) {
            StatusClient(this@ProxyActivity).serviceStatus().profileLoaded
        }
        if (!ready) {
            finish()
            return
        }

        val mode = withClash { queryOverride(Clash.OverrideSlot.Session).mode }
        val names = withClash { queryProxyGroupNames(uiStore.proxyExcludeNotSelectable) }
        val states = List(names.size) { ProxyState("?") }
        val unorderedStates = names.indices.map { names[it] to states[it] }.toMap()
        val reloadLock = Semaphore(10)
        val reloadGenerations = IntArray(names.size)

        val design = ProxyDesign(
            this,
            mode,
            names,
            states,
            uiStore
        )

        setContentDesign(design)

        design.requests.send(ProxyDesign.Request.ReloadAll)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart -> design.requestRedrawVisible()
                        Event.ProfileLoading,
                        Event.ClashStop,
                        Event.ServiceRecreated -> finish()
                        Event.ProfileLoaded -> {
                            val newNames = withClash {
                                queryProxyGroupNames(uiStore.proxyExcludeNotSelectable)
                            }

                            if (newNames != names) {
                                startActivity(ProxyActivity::class.intent)

                                finish()
                            } else {
                                design.requests.trySend(ProxyDesign.Request.ReloadAll)
                            }
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ProxyDesign.Request.ReLaunch -> {
                            startActivity(ProxyActivity::class.intent)

                            finish()
                        }
                        ProxyDesign.Request.ReloadAll -> {
                            names.indices.forEach { idx ->
                                val generation = ++reloadGenerations[idx]
                                design.requests.trySend(ProxyDesign.Request.Reload(idx, generation))
                            }
                        }
                        is ProxyDesign.Request.Reload -> {
                            launch {
                                if (it.generation != reloadGenerations[it.index]) return@launch

                                // Preserve results that arrive after this snapshot request starts.
                                design.prepareGroupReload(it.index)
                                val group = reloadLock.withPermit {
                                    withClash {
                                        queryProxyGroup(names[it.index], uiStore.proxySort)
                                    }
                                }

                                if (it.generation != reloadGenerations[it.index]) return@launch

                                val state = states[it.index]

                                state.now = group.now

                                design.updateGroup(
                                    it.index,
                                    group.proxies,
                                    group.type == "Selector" || group.type == "Fallback",
                                    state,
                                    unorderedStates
                                )
                            }
                        }
                        is ProxyDesign.Request.Select -> {
                            val patched = withClash {
                                patchSelector(names[it.index], it.name)
                            }
                            if (patched) {
                                val generation = ++reloadGenerations[it.index]
                                design.requests.send(ProxyDesign.Request.Reload(it.index, generation))
                            } else {
                                design.requests.send(ProxyDesign.Request.ReloadAll)
                            }
                        }
                        is ProxyDesign.Request.UrlTest -> {
                            launch testing@ {
                                val progress = ProxyDelayUpdates()
                                val updater = launch {
                                    try {
                                        for (signal in progress.available) {
                                            design.updateDelays(it.index, progress.drain(), activityStarted)
                                        }
                                    } finally {
                                        progress.cancel()
                                    }
                                }
                                try {
                                    withClash {
                                        val observer = object : IUrlTestObserver {
                                            override fun onResults(results: List<ProxyDelay>): Boolean =
                                                this@testing.isActive && progress.offer(results)
                                        }
                                        healthCheck(names[it.index], observer, it.name)
                                    }

                                    progress.close()
                                    updater.join()
                                    val generation = ++reloadGenerations[it.index]
                                    if (it.name == null) {
                                        // Group tests sort once at the end. Single-node tests retain position.
                                        val group = withClash {
                                            queryProxyGroup(names[it.index], uiStore.proxySort)
                                        }
                                        if (generation == reloadGenerations[it.index]) {
                                            val state = states[it.index]
                                            state.now = group.now
                                            design.updateGroup(
                                                it.index, group.proxies,
                                                group.type == "Selector" || group.type == "Fallback",
                                                state, unorderedStates
                                            )
                                        }
                                    } else {
                                        // A test can change fallback selection. Read only that small value.
                                        val now = withClash { queryProxyGroupNow(names[it.index]) }
                                        if (generation == reloadGenerations[it.index] && now != null) {
                                            if (states[it.index].now != now) {
                                                states[it.index].now = now
                                                design.requestRedrawVisible()
                                            }
                                        }
                                    }
                                } finally {
                                    progress.cancel()
                                    updater.cancel()
                                    if (it.name == null) design.finishUrlTesting(it.index)
                                    else design.finishNodeTesting(it.index, it.name)
                                }
                            }
                        }
                        is ProxyDesign.Request.PatchMode -> {
                            design.showModeSwitchTips()

                            withClash {
                                val o = queryOverride(Clash.OverrideSlot.Session)

                                o.mode = it.mode

                                patchOverride(Clash.OverrideSlot.Session, o)
                            }
                        }
                    }
                }
            }
        }
    }
}
