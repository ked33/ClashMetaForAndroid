package com.github.kr328.clash.core.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.Serializable

@Serializable
data class ProxyDelay(val name: String, val delay: Int) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ProxyDelay> {
        override fun createFromParcel(parcel: Parcel): ProxyDelay =
            Parcelizer.decodeFromParcel(serializer(), parcel)

        override fun newArray(size: Int): Array<ProxyDelay?> = arrayOfNulls(size)
    }
}
