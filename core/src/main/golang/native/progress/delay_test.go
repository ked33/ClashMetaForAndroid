package progress

import (
	"fmt"
	"testing"
	"time"
)

func receiveBatch(t *testing.T, batches <-chan []Delay) []Delay {
	t.Helper()
	select {
	case batch := <-batches:
		return batch
	case <-time.After(2 * time.Second):
		t.Fatal("progress blocked waiting for unfinished nodes")
		return nil
	}
}

func TestStreamReportsBeforeSlowNodeFinishes(t *testing.T) {
	results := make(chan Delay)
	batches := make(chan []Delay, 8)
	done := make(chan struct{})
	go func() {
		Stream(results, func(batch []Delay) bool {
			batches <- batch
			return true
		}, func() {})
		close(done)
	}()
	results <- Delay{Name: "fast", Delay: 20}
	if batch := receiveBatch(t, batches); len(batch) != 1 || batch[0].Name != "fast" {
		t.Fatalf("unexpected first result: %v", batch)
	}
	// Keep the source open: a later completed node must not wait for the slow one.
	results <- Delay{Name: "medium", Delay: 100}
	if batch := receiveBatch(t, batches); len(batch) != 1 || batch[0].Name != "medium" {
		t.Fatalf("unexpected incremental result: %v", batch)
	}
	results <- Delay{Name: "timeout", Delay: 65535}
	close(results)
	if batch := receiveBatch(t, batches); len(batch) != 1 || batch[0].Delay != 65535 {
		t.Fatalf("lost final failure: %v", batch)
	}
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("stream did not complete")
	}
}

func TestStreamBoundsBatchesAndDrainsAllResults(t *testing.T) {
	results := make(chan Delay, 1000)
	for i := 0; i < cap(results); i++ {
		results <- Delay{Name: fmt.Sprint(i), Delay: i}
	}
	close(results)
	count, calls := 0, 0
	Stream(results, func(batch []Delay) bool {
		calls++
		if len(batch) == 0 || len(batch) > batchSize {
			t.Fatalf("unbounded batch: %d", len(batch))
		}
		for _, result := range batch {
			if result.Delay != count {
				t.Fatalf("missing/reordered result: got %d, want %d", result.Delay, count)
			}
			count++
		}
		return true
	}, func() { t.Fatal("unexpected cancellation") })
	// Leave room for scheduler pauses under the race detector; still reject per-node IPC.
	if count != cap(results) || calls >= count/2 {
		t.Fatalf("count=%d callbacks=%d", count, calls)
	}
}

func TestStreamDetachesAndUnblocksProducers(t *testing.T) {
	results := make(chan Delay)
	done := make(chan struct{})
	go func() {
		defer close(done)
		defer close(results)
		for i := 0; i < 200; i++ {
			results <- Delay{Delay: i}
		}
	}()
	calls, cancels := 0, 0
	Stream(results, func([]Delay) bool {
		calls++
		return false
	}, func() { cancels++ })
	if calls != 1 || cancels != 1 {
		t.Fatalf("observer called after detach: calls=%d cancels=%d", calls, cancels)
	}
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("producer left blocked after observer detached")
	}
}

func TestStreamEmptyGroupDoesNotReport(t *testing.T) {
	results := make(chan Delay)
	close(results)
	Stream(results, func([]Delay) bool {
		t.Fatal("empty callback")
		return true
	}, func() { t.Fatal("unexpected cancellation") })
}
