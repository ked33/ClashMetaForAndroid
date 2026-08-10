package com.github.kr328.clash

import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.ProxyDesign
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ProxyActivity : BaseActivity<ProxyDesign>() {
    override suspend fun main() {
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
            uiStore
        )

        setContentDesign(design)

        design.requests.send(ProxyDesign.Request.ReloadAll)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
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
                                    group.type == "Selector",
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
                                reloadGenerations[it.index]++
                                states[it.index].now = it.name
                                design.requestRedrawVisible()
                            } else {
                                design.requests.send(ProxyDesign.Request.ReloadAll)
                            }
                        }
                        is ProxyDesign.Request.UrlTest -> {
                            launch {
                                withClash {
                                    healthCheck(names[it.index])
                                }

                                val generation = ++reloadGenerations[it.index]
                                design.requests.send(
                                    ProxyDesign.Request.Reload(it.index, generation)
                                )
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
