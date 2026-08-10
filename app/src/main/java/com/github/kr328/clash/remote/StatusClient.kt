package com.github.kr328.clash.remote

import android.content.Context
import android.net.Uri
import com.github.kr328.clash.common.constants.Authorities
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.StatusProvider

class StatusClient(private val context: Context) {
    data class ServiceStatus(
        val running: Boolean,
        val currentProfile: String?,
    ) {
        val profileLoaded: Boolean
            get() = currentProfile != null
    }

    private val uri: Uri
        get() {
            return Uri.Builder()
                .scheme("content")
                .authority(Authorities.STATUS_PROVIDER)
                .build()
        }

    fun serviceStatus(): ServiceStatus {
        return try {
            val result = context.contentResolver.call(
                uri,
                StatusProvider.METHOD_CURRENT_PROFILE,
                null,
                null
            )

            ServiceStatus(
                running = result != null,
                currentProfile = result?.getString("name"),
            )
        } catch (e: Exception) {
            Log.w("Query current profile: $e", e)

            ServiceStatus(running = false, currentProfile = null)
        }
    }
}
