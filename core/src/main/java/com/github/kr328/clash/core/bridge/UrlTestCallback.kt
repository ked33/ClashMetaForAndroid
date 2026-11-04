package com.github.kr328.clash.core.bridge

import androidx.annotation.Keep

@Keep
fun interface UrlTestCallback {
    /** False detaches the observer and cancels the remaining tests. */
    fun report(resultsJson: String): Boolean
}
