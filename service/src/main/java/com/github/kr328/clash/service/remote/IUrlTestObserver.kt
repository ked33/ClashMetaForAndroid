package com.github.kr328.clash.service.remote

import com.github.kr328.clash.core.model.ProxyDelay
import com.github.kr328.kaidl.BinderInterface

@BinderInterface
fun interface IUrlTestObserver {
    fun onResults(results: List<ProxyDelay>): Boolean
}
