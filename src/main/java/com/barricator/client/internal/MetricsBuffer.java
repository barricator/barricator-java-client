package com.barricator.client.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe, lock-free aggregation of evaluation telemetry. Each {@code (flagKey, variationId,
 * defaulted)} combination maps to a {@link LongAdder} counter, so recording an evaluation on the hot
 * path is a contention-friendly increment. {@link #drain()} atomically snapshots and resets the
 * counters for the background flush worker.
 */
public final class MetricsBuffer {

    /** One aggregated counter line, matching the backend {@code MetricFlushRequest.MetricEvent}. */
    public record MetricEvent(String flagKey, String variationId, long count, boolean defaulted) {
    }

    private final ConcurrentHashMap<Key, LongAdder> counters = new ConcurrentHashMap<>();

    public void record(String flagKey, String variationId, boolean defaulted) {
        counters.computeIfAbsent(new Key(flagKey, variationId, defaulted), k -> new LongAdder()).increment();
    }

    /** Snapshots and clears the buffer. Returns the aggregated events to flush. */
    public List<MetricEvent> drain() {
        List<MetricEvent> events = new ArrayList<>();
        for (Map.Entry<Key, LongAdder> e : counters.entrySet()) {
            long count = e.getValue().sumThenReset();
            if (count > 0) {
                Key k = e.getKey();
                events.add(new MetricEvent(k.flagKey, k.variationId, count, k.defaulted));
            }
        }
        // Remove zeroed keys to bound memory for flags no longer evaluated.
        counters.entrySet().removeIf(e -> e.getValue().sum() == 0);
        return events;
    }

    private record Key(String flagKey, String variationId, boolean defaulted) {
    }
}
