package tunnel

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
	"time"

	"cfa/native/progress"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/common/utils"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

type testOptions struct {
	TestURL        string `json:"testUrl"`
	ExpectedStatus string `json:"expectedStatus"`
}

func groupTestOptions(g outboundgroup.ProxyGroup) (testOptions, error) {
	options := testOptions{}
	data, err := g.MarshalJSON()
	if err == nil {
		err = json.Unmarshal(data, &options)
	}
	options.TestURL = strings.TrimSpace(options.TestURL)
	if options.TestURL == "" {
		options.TestURL = C.DefaultTestURL
	}
	return options, err
}

func HealthCheck(name string, report func([]progress.Delay) bool, proxyName *string) {
	healthCheck(name, true, report, proxyName)
}

func healthCheck(name string, manual bool, report func([]progress.Delay) bool, proxyName *string) {
	p := tunnel.Proxies()[name]

	if p == nil {
		log.Warnln("Request health check for `%s`: not found", name)

		return
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		log.Warnln("Request health check for `%s`: invalid type %s", name, p.Type().String())

		return
	}

	if manual {
		// Manual tests must also work when providers have no health-check URL.
		options, err := groupTestOptions(g)
		if err != nil {
			log.Warnln("Request health check for `%s`: %s", name, err)
			return
		}
		expectedStatus, err := utils.NewUnsignedRanges[uint16](options.ExpectedStatus)
		if err != nil {
			log.Warnln("Request health check for `%s`: %s", name, err)
			return
		}

		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if report != nil || proxyName != nil {
			// Same members and URLTest semantics as GroupBase, with per-result progress.
			proxies := g.Proxies()
			if proxyName != nil {
				// Provider nodes are not necessarily in tunnel.Proxies(). Resolve
				// within the displayed group, and never fall back to testing all.
				var selected C.Proxy
				for _, proxy := range proxies {
					if proxy.Name() == *proxyName {
						selected = proxy
						break
					}
				}
				if selected == nil {
					log.Warnln("Request node health check: group member not found")
					return
				}
				proxies = []C.Proxy{selected}
			}
			results := make(chan progress.Delay, 64)
			var wg sync.WaitGroup
			for _, proxy := range proxies {
				wg.Add(1)
				go func(proxy C.Proxy) {
					defer wg.Done()
					_, _ = proxy.URLTest(ctx, options.TestURL, expectedStatus)
					// URLTest commits history before returning, including failed status checks.
					results <- progress.Delay{
						Name:  proxy.Name(),
						Delay: int(proxy.LastDelayForTestUrl(options.TestURL)),
					}
				}(proxy)
			}
			go func() {
				wg.Wait()
				close(results)
			}()
			progress.Stream(results, report, cancel)
			return
		}
		if _, err := g.URLTest(ctx, options.TestURL, expectedStatus); err != nil {
			log.Debugln("Request health check for `%s`: %s", name, err)
		}
		return
	}

	wg := &sync.WaitGroup{}

	for _, pr := range g.Providers() {
		wg.Add(1)

		go func(provider provider.ProxyProvider) {
			provider.HealthCheck()

			wg.Done()
		}(pr)
	}

	wg.Wait()
}

func HealthCheckAll() {
	for _, g := range QueryProxyGroupNames(false) {
		go func(group string) {
			healthCheck(group, false, nil, nil)
		}(g)
	}
}
