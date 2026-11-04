package tunnel

import (
	"sync"

	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/tunnel"
)

type NotificationProxy struct {
	Name string `json:"name"`
	Type string `json:"type"`
}

var notificationRoute struct {
	sync.Mutex
	matches []C.Rule
}

// QueryNotificationProxy reads only the default outbound, without creating a
// connection or touching provider health checks. MATCH rules are cached per load.
func QueryNotificationProxy(refresh bool) NotificationProxy {
	notificationRoute.Lock()
	defer notificationRoute.Unlock()

	if refresh {
		notificationRoute.matches = nil
		for _, rule := range tunnel.Rules() {
			if rule.RuleType() == C.MATCH {
				notificationRoute.matches = append(notificationRoute.matches, rule)
			}
		}
	}
	proxies := tunnel.Proxies()
	switch tunnel.Mode() {
	case tunnel.Direct:
		return notificationLeaf(proxies["DIRECT"])
	case tunnel.Global:
		return notificationLeaf(proxies["GLOBAL"])
	default:
		for _, rule := range notificationRoute.matches {
			if wrapper, ok := rule.(C.RuleWrapper); ok && wrapper.IsDisabled() {
				continue
			}
			proxy := proxies[rule.Adapter()]
			if proxy == nil {
				continue
			}
			leaf := notificationLeaf(proxy)
			if leaf.Type != C.Pass.String() {
				return leaf
			}
		}
		// This is also mihomo's fallback when there is no matching rule.
		return notificationLeaf(proxies["DIRECT"])
	}
}

func notificationLeaf(proxy C.Proxy) NotificationProxy {
	// Bound malformed/cyclic nesting without allocating a visited map each second.
	var visited [32]string
	for depth := 0; proxy != nil && depth < len(visited); depth++ {
		name := proxy.Name()
		for _, previous := range visited[:depth] {
			if previous == name {
				return NotificationProxy{}
			}
		}
		visited[depth] = name
		switch proxy.Type() {
		case C.Selector, C.Fallback, C.URLTest:
			proxy = proxy.Unwrap(nil, false)
		default:
			// In particular, never unwrap LoadBalance: selecting a synthetic
			// destination can advance round-robin state and invent a "current" node.
			return NotificationProxy{Name: name, Type: proxy.Type().String()}
		}
	}
	return NotificationProxy{}
}
