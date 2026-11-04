package progress

import "time"

type Delay struct {
	Name  string `json:"name"`
	Delay int    `json:"delay"`
}

const (
	batchInterval = 50 * time.Millisecond
	batchSize     = 64
)

// Stream reports the first result immediately, then coalesces bursts before
// crossing JNI/Binder. It never polls, and drains producers after cancellation.
func Stream(results <-chan Delay, report func([]Delay) bool, cancel func()) {
	var lastReport time.Time
	for result := range results {
		if report == nil {
			continue
		}
		batch := make([]Delay, 1, batchSize)
		batch[0] = result
		if wait := time.Until(lastReport.Add(batchInterval)); wait > 0 {
			timer := time.NewTimer(wait)
		collect:
			for len(batch) < batchSize {
				select {
				case next, ok := <-results:
					if !ok {
						break collect
					}
					batch = append(batch, next)
				case <-timer.C:
					break collect
				}
			}
			timer.Stop()
		}
		if !report(batch) {
			report = nil
			cancel()
		}
		lastReport = time.Now()
	}
}
