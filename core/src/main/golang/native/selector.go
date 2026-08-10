package main

/*
#include "bridge.h"
*/
import "C"

import (
	"sync"
	"unsafe"

	"cfa/native/tunnel"

	"github.com/metacubex/mihomo/hub/route"
	"github.com/metacubex/mihomo/log"
)

var (
	selectorUpdateMutex    sync.RWMutex
	selectorUpdateListener unsafe.Pointer
)

func init() {
	route.SwitchProxiesCallback = notifySelectorUpdated
}

func notifySelectorUpdated(group, _ string) {
	// REST callbacks run asynchronously; read the current value so an older callback
	// cannot overwrite a newer selection when requests arrive in quick succession.
	current := tunnel.QueryProxyGroup(group, tunnel.Default, nil)
	if current == nil || current.Now == "" {
		return
	}

	selectorUpdateMutex.RLock()
	defer selectorUpdateMutex.RUnlock()

	if selectorUpdateListener == nil {
		return
	}

	if C.selector_updated(
		selectorUpdateListener,
		C.CString(group),
		C.CString(current.Now),
	) != 0 {
		log.Warnln("[APP] Persist selector update callback failed")
	}
}

//export setSelectorUpdateListener
func setSelectorUpdateListener(listener unsafe.Pointer) {
	selectorUpdateMutex.Lock()
	defer selectorUpdateMutex.Unlock()

	if selectorUpdateListener != nil {
		C.release_object(selectorUpdateListener)
	}

	selectorUpdateListener = listener
}
