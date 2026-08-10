package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.os.SystemClock
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.clash.SelectionDebugLog
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileLoaded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.*

class ConfigurationModule(service: Service) : Module<ConfigurationModule.LoadException>(service) {
    data class LoadException(val message: String)

    private val store = ServiceStore(service)
    private val selectionDebug = SelectionDebugLog(store)
    private val reload = Channel<Unit>(Channel.CONFLATED)
    private var activeDebugCycle = "none"

    @Volatile
    private var activeDebugStage = "idle"

    suspend fun debugAudit(phase: String) {
        val stage = activeDebugStage
        if (stage != "loaded") {
            selectionDebug.summary(
                name = "audit_skipped",
                dedupeKey = "$activeDebugCycle|$phase|$stage",
                fields = "cycle=$activeDebugCycle profile=${selectionDebug.profile(store.activeProfile)} phase=$phase stage=$stage reason=profile_not_loaded",
            )
            selectionDebug.flush()
            return
        }

        selectionDebug.audit(store.activeProfile, phase, activeDebugCycle)
    }

    override suspend fun run() {
        val broadcasts = receiveBroadcast {
            addAction(Intents.ACTION_PROFILE_CHANGED)
            addAction(Intents.ACTION_OVERRIDE_CHANGED)
        }

        var loaded: UUID? = null
        var auditJob: Job? = null

        reload.trySend(Unit)

        while (true) {
            val changed: UUID? = select {
                broadcasts.onReceive {
                    if (it.action == Intents.ACTION_PROFILE_CHANGED)
                        UUID.fromString(it.getStringExtra(Intents.EXTRA_UUID))
                    else
                        null
                }
                reload.onReceive {
                    null
                }
            }

            var profile = "none"
            var stage = "resolve_active_profile"

            try {
                val current = store.activeProfile
                    ?: throw NullPointerException("No profile selected")
                profile = selectionDebug.profile(current)

                if (current == loaded && changed != null && changed != loaded)
                    continue

                auditJob?.cancel()
                loaded = current
                val cycle = SystemClock.elapsedRealtime().toString()
                activeDebugCycle = cycle
                activeDebugStage = stage

                selectionDebug.summary(
                    name = "load_start",
                    dedupeKey = "$profile|$cycle",
                    fields = "cycle=$cycle profile=$profile requested=${selectionDebug.profile(changed)}",
                )

                stage = "query_imported_profile"
                activeDebugStage = stage
                val active = ImportedDao().queryByUUID(current)
                    ?: throw NullPointerException("No profile selected")

                stage = "set_age_key"
                activeDebugStage = stage
                Clash.setAgeSecretKey(active.ageSecretKey?.takeIf { it.isNotBlank() })

                stage = "detach_listener"
                activeDebugStage = stage
                Clash.setSelectorUpdateListener(null)
                selectionDebug.event(
                    name = "listener_detached",
                    dedupeKey = "$profile|$cycle",
                    fields = "cycle=$cycle profile=$profile",
                )

                stage = "native_load"
                activeDebugStage = stage
                val loadStartedAt = SystemClock.elapsedRealtime()
                Clash.load(service.importedDir.resolve(active.uuid.toString())).await()
                selectionDebug.summary(
                    name = "native_load_complete",
                    dedupeKey = "$profile|$cycle",
                    fields = "cycle=$cycle profile=$profile elapsed_ms=${SystemClock.elapsedRealtime() - loadStartedAt}",
                )

                stage = "query_saved_selections"
                activeDebugStage = stage
                val selectionDao = SelectionDao()
                val saved = selectionDao.querySelections(active.uuid)
                selectionDebug.summary(
                    name = "restore_database_loaded",
                    dedupeKey = "$profile|$cycle",
                    fields = "cycle=$cycle profile=$profile saved=${saved.size}",
                )

                stage = "restore_selections"
                activeDebugStage = stage
                val remove = mutableListOf<String>()
                var patched = 0
                var verified = 0
                var mismatched = 0
                var verificationErrors = 0
                val debugEnabled = selectionDebug.enabled

                saved.forEachIndexed { index, selection ->
                    val patchResult = Clash.patchSelector(selection.proxy, selection.selected)
                    if (patchResult) patched++ else remove += selection.proxy

                    var actual: String? = null
                    var verificationError: Exception? = null
                    if (patchResult && debugEnabled) {
                        try {
                            actual = Clash.querySelectorNow(selection.proxy)
                            if (actual == selection.selected) verified++ else mismatched++
                        } catch (e: Exception) {
                            verificationError = e
                            verificationErrors++
                        }
                    }

                    if (index < MAX_RESTORE_DETAILS) {
                        val fields = buildString {
                            append("cycle=$cycle profile=$profile")
                            append(" group=${selectionDebug.token(selection.proxy)}")
                            append(" saved=${selectionDebug.token(selection.selected)}")
                            append(" patched=$patchResult")
                            append(" actual=${selectionDebug.tokenOrNone(actual)}")
                            append(" verified=${patchResult && actual == selection.selected}")
                            verificationError?.let {
                                append(" verify_error=${selectionDebug.error(it)}")
                            }
                        }
                        selectionDebug.event(
                            name = "restore_item",
                            dedupeKey = "$profile|$cycle|${selectionDebug.token(selection.proxy)}",
                            fields = fields,
                        )
                    }
                }

                stage = "remove_invalid_selections"
                activeDebugStage = stage
                selectionDao.removeSelections(active.uuid, remove)
                selectionDebug.summary(
                    name = "restore_complete",
                    dedupeKey = "$profile|$cycle",
                    fields = "cycle=$cycle profile=$profile saved=${saved.size} patched=$patched verified=$verified mismatch=$mismatched verify_errors=$verificationErrors removed=${remove.size} details=${minOf(saved.size, MAX_RESTORE_DETAILS)} omitted=${(saved.size - MAX_RESTORE_DETAILS).coerceAtLeast(0)}",
                )

                stage = "bind_listener"
                activeDebugStage = stage
                Clash.setSelectorUpdateListener { group, selected ->
                    val groupToken = selectionDebug.token(group)
                    val selectedToken = selectionDebug.token(selected)
                    selectionDebug.event(
                        name = "rest_callback_received",
                        dedupeKey = "$profile|$cycle|$groupToken|$selectedToken",
                        fields = "cycle=$cycle profile=$profile group=$groupToken selected=$selectedToken",
                    )

                    try {
                        selectionDao.setSelected(Selection(active.uuid, group, selected))
                        selectionDebug.event(
                            name = "rest_database_result",
                            dedupeKey = "$profile|$cycle|$groupToken|$selectedToken",
                            fields = "cycle=$cycle profile=$profile group=$groupToken selected=$selectedToken action=upsert status=ok",
                        )
                    } catch (e: Exception) {
                        selectionDebug.summary(
                            name = "rest_database_failed",
                            dedupeKey = "$profile|$cycle|$groupToken",
                            fields = "cycle=$cycle profile=$profile group=$groupToken selected=$selectedToken action=upsert error=${selectionDebug.error(e)}",
                        )
                        selectionDebug.flush()
                        Log.w("Persist selector update failed", e)
                    }
                }
                selectionDebug.summary(
                    name = "listener_bound",
                    dedupeKey = "$profile|$cycle",
                    fields = "cycle=$cycle profile=$profile",
                )

                stage = "publish_loaded_profile"
                activeDebugStage = stage
                StatusProvider.currentProfile = active.name

                stage = "loaded"
                activeDebugStage = stage

                service.sendProfileLoaded(current)

                selectionDebug.summary(
                    name = "load_complete",
                    dedupeKey = "$profile|$cycle",
                    fields = "cycle=$cycle profile=$profile",
                )
                selectionDebug.flush()

                val auditScope = CoroutineScope(currentCoroutineContext())
                auditJob = auditScope.launch {
                    var previousDelay = 0L
                    POST_LOAD_AUDITS.forEach { (delayMillis, phase) ->
                        delay(delayMillis - previousDelay)
                        selectionDebug.audit(active.uuid, phase, cycle)
                        previousDelay = delayMillis
                    }
                }

                Log.d("Profile ${active.name} loaded")
            } catch (e: CancellationException) {
                selectionDebug.summary(
                    name = "load_cancelled",
                    dedupeKey = "$profile|$activeDebugCycle|$stage",
                    fields = "cycle=$activeDebugCycle profile=$profile stage=$stage",
                )
                selectionDebug.flush()
                activeDebugStage = "cancelled"
                throw e
            } catch (e: Exception) {
                selectionDebug.summary(
                    name = "load_failed",
                    dedupeKey = "$profile|$activeDebugCycle|$stage",
                    fields = "cycle=$activeDebugCycle profile=$profile stage=$stage error=${selectionDebug.error(e)}",
                )
                selectionDebug.flush()
                activeDebugStage = "failed"
                return enqueueEvent(LoadException(e.message ?: "Unknown"))
            }
        }
    }

    companion object {
        private const val MAX_RESTORE_DETAILS = 16

        private val POST_LOAD_AUDITS = listOf(
            500L to "post_load_500ms",
            2_000L to "post_load_2s",
            5_000L to "post_load_5s",
        )
    }
}
