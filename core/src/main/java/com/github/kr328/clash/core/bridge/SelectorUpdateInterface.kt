package com.github.kr328.clash.core.bridge

import androidx.annotation.Keep

@Keep
interface SelectorUpdateInterface {
    fun updated(group: String, selected: String)
}
