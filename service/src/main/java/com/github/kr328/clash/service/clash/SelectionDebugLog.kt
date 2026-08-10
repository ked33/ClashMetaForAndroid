package com.github.kr328.clash.service.clash

import android.os.SystemClock
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.store.ServiceStore
import java.util.UUID

class SelectionDebugLog(private val store: ServiceStore) {
    val enabled: Boolean
        get() = store.selectorPersistenceDebug

    fun event(name: String, dedupeKey: String = name, fields: String = "") {
        emit(name, dedupeKey, fields, essential = false)
    }

    fun summary(name: String, dedupeKey: String = name, fields: String = "") {
        emit(name, dedupeKey, fields, essential = true)
    }

    suspend fun audit(profileUuid: UUID?, phase: String, cycle: String = "none") {
        if (!enabled) return

        val audit = SystemClock.elapsedRealtime().toString()
        if (profileUuid == null) {
            summary(
                name = "audit_skipped",
                dedupeKey = "$cycle|$phase|$audit",
                fields = "cycle=$cycle audit=$audit phase=$phase reason=no_active_profile",
            )
            flush()
            return
        }

        val profile = profile(profileUuid)
        val selections = try {
            SelectionDao().querySelections(profileUuid)
        } catch (e: Exception) {
            summary(
                name = "audit_failed",
                dedupeKey = "$cycle|$phase|$audit|database",
                fields = "cycle=$cycle audit=$audit phase=$phase profile=$profile stage=database error=${error(e)}",
            )
            flush()
            return
        }

        val runtimeGroupCount = try {
            Clash.queryGroupNames(excludeNotSelectable = false).size
        } catch (e: Exception) {
            summary(
                name = "audit_failed",
                dedupeKey = "$cycle|$phase|$audit|runtime_groups",
                fields = "cycle=$cycle audit=$audit phase=$phase profile=$profile stage=runtime_groups error=${error(e)}",
            )
            flush()
            return
        }

        summary(
            name = "audit_begin",
            dedupeKey = "$cycle|$phase|$audit",
            fields = "cycle=$cycle audit=$audit phase=$phase profile=$profile saved=${selections.size} runtime_groups=$runtimeGroupCount",
        )

        var matches = 0
        var mismatches = 0
        var missing = 0
        var errors = 0
        var details = 0

        selections.forEach { selection ->
            val state = try {
                Clash.querySelectorNow(selection.proxy)
            } catch (e: Exception) {
                errors++
                if (details < MAX_AUDIT_DETAILS) {
                    event(
                        name = "audit_item",
                        dedupeKey = "$cycle|$phase|$audit|${token(selection.proxy)}",
                        fields = "cycle=$cycle audit=$audit phase=$phase group=${token(selection.proxy)} saved=${token(selection.selected)} status=error error=${error(e)}",
                    )
                    details++
                }
                return@forEach
            }

            val status = when {
                state == null -> {
                    missing++
                    "missing"
                }
                state == selection.selected -> {
                    matches++
                    "match"
                }
                else -> {
                    mismatches++
                    "mismatch"
                }
            }

            if (details < MAX_AUDIT_DETAILS) {
                event(
                    name = "audit_item",
                    dedupeKey = "$cycle|$phase|$audit|${token(selection.proxy)}",
                    fields = "cycle=$cycle audit=$audit phase=$phase group=${token(selection.proxy)} saved=${token(selection.selected)} actual=${tokenOrNone(state)} status=$status",
                )
                details++
            }
        }

        summary(
            name = "audit_complete",
            dedupeKey = "$cycle|$phase|$audit",
            fields = "cycle=$cycle audit=$audit phase=$phase profile=$profile saved=${selections.size} match=$matches mismatch=$mismatches missing=$missing errors=$errors details=$details omitted=${(selections.size - details).coerceAtLeast(0)}",
        )
        flush()
    }

    fun profile(uuid: UUID?): String {
        return uuid?.toString()?.take(8) ?: "none"
    }

    fun token(value: String): String {
        return "${Integer.toHexString(value.hashCode()).padStart(8, '0')}/${value.length}"
    }

    fun tokenOrNone(value: String?): String {
        return value?.takeIf { it.isNotEmpty() }?.let(::token) ?: "none"
    }

    fun error(error: Throwable): String {
        return "${error.javaClass.simpleName}/${tokenOrNone(error.message)}"
    }

    private fun emit(
        name: String,
        dedupeKey: String,
        fields: String,
        essential: Boolean,
    ) {
        if (!enabled) return

        val output = mutableListOf<String>()
        val now = SystemClock.elapsedRealtime()

        synchronized(lock) {
            rotateWindow(now, output)

            val key = "$name|$dedupeKey"
            val previous = recent[key]
            if (previous != null && now - previous < DEDUP_WINDOW_MILLIS) {
                recent[key] = now
                deduplicated[name] = (deduplicated[name] ?: 0) + 1
                return@synchronized
            }

            recent[key] = now
            trimRecent()

            val exhausted = emittedInWindow >= MAX_EVENTS_BEFORE_SUPPRESSION ||
                (!essential && normalInWindow >= MAX_NORMAL_EVENTS_PER_WINDOW)
            if (exhausted) {
                rateLimited++
                return@synchronized
            }

            output += buildMessage(name, fields)
            emittedInWindow++
            if (!essential) normalInWindow++
        }

        output.forEach(::writeSafely)
    }

    fun flush() {
        if (!enabled) return

        val output = synchronized(lock) {
            if (emittedInWindow >= MAX_EVENTS_PER_WINDOW) return@synchronized null

            buildSuppressionSummary()?.also {
                emittedInWindow++
                deduplicated.clear()
                rateLimited = 0
            }
        }

        output?.let(::writeSafely)
    }

    private fun buildMessage(name: String, fields: String): String {
        val safeFields = fields
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(MAX_FIELDS_LENGTH)
            .trim()

        return if (safeFields.isEmpty()) "event=$name" else "event=$name $safeFields"
    }

    private fun rotateWindow(now: Long, output: MutableList<String>) {
        if (now - windowStartedAt < RATE_WINDOW_MILLIS) return

        buildSuppressionSummary()?.let {
            output += it
        }
        windowStartedAt = now
        emittedInWindow = output.size
        normalInWindow = 0
        rateLimited = 0
        deduplicated.clear()
    }

    private fun buildSuppressionSummary(): String? {
        val duplicateTotal = deduplicated.values.sum()
        if (duplicateTotal == 0 && rateLimited == 0) return null

        val duplicateTypes = deduplicated.entries
            .sortedByDescending { it.value }
            .take(MAX_SUMMARY_TYPES)
            .joinToString(",") { "${it.key}:${it.value}" }
            .ifEmpty { "none" }

        return "event=log_suppression duplicates=$duplicateTotal rate_limited=$rateLimited types=$duplicateTypes"
    }

    private fun trimRecent() {
        while (recent.size > MAX_RECENT_KEYS) {
            val iterator = recent.entries.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    private fun writeSafely(message: String) {
        try {
            Clash.logAppDebug(message)
        } catch (_: Throwable) {
            // Diagnostics must never alter selector or service behavior.
        }
    }

    companion object {
        private const val DEDUP_WINDOW_MILLIS = 1_500L
        private const val RATE_WINDOW_MILLIS = 10_000L
        private const val MAX_EVENTS_PER_WINDOW = 48
        private const val MAX_EVENTS_BEFORE_SUPPRESSION = MAX_EVENTS_PER_WINDOW - 1
        private const val MAX_NORMAL_EVENTS_PER_WINDOW = 31
        private const val MAX_RECENT_KEYS = 128
        private const val MAX_SUMMARY_TYPES = 6
        private const val MAX_FIELDS_LENGTH = 512
        private const val MAX_AUDIT_DETAILS = 16

        private val lock = Any()
        private val recent = LinkedHashMap<String, Long>(MAX_RECENT_KEYS, 0.75f, true)
        private val deduplicated = mutableMapOf<String, Int>()
        private var windowStartedAt = SystemClock.elapsedRealtime()
        private var emittedInWindow = 0
        private var normalInWindow = 0
        private var rateLimited = 0
    }
}
