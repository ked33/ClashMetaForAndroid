package tunnel

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
	"time"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/common/utils"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

func HealthCheck(name string) {
	healthCheck(name, true)
}

func healthCheck(name string, manual bool) {
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
		options := struct {
			TestURL        string `json:"testUrl"`
			ExpectedStatus string `json:"expectedStatus"`
		}{}
		data, err := g.MarshalJSON()
		if err == nil {
			err = json.Unmarshal(data, &options)
		}
		if err != nil {
			log.Warnln("Request health check for `%s`: %s", name, err)
			return
		}
		options.TestURL = strings.TrimSpace(options.TestURL)
		if options.TestURL == "" {
			options.TestURL = C.DefaultTestURL
		}
		expectedStatus, err := utils.NewUnsignedRanges[uint16](options.ExpectedStatus)
		if err != nil {
			log.Warnln("Request health check for `%s`: %s", name, err)
			return
		}

		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
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
			healthCheck(group, false)
		}(g)
	}
}
