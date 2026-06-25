package com.barricador.client.internal;

import com.barricador.client.model.FlagModels.FeatureFlag;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe in-memory cache of the environment ruleset. Reads ({@link #get}) are lock-free O(1)
 * lookups against a {@link ConcurrentHashMap}; bootstrap performs a bulk {@link #replaceAll}, while
 * SSE deltas {@link #upsert}/{@link #remove} individual flags. This is the state evaluation reads,
 * and the same state served during network outages.
 */
public final class FlagStore {

    private final ConcurrentHashMap<String, FeatureFlag> flags = new ConcurrentHashMap<>();
    private final AtomicLong rulesVersion = new AtomicLong(0);

    public FeatureFlag get(String key) {
        return flags.get(key);
    }

    public boolean isInitialized() {
        return !flags.isEmpty() || rulesVersion.get() > 0;
    }

    public long rulesVersion() {
        return rulesVersion.get();
    }

    /** Atomically swaps the entire ruleset (used on bootstrap and full re-sync). */
    public void replaceAll(Map<String, FeatureFlag> newFlags, long version) {
        Map<String, FeatureFlag> snapshot = new HashMap<>(newFlags);
        flags.keySet().retainAll(snapshot.keySet());
        flags.putAll(snapshot);
        rulesVersion.set(version);
    }

    /** Applies a single-flag delta from the SSE stream. */
    public void upsert(FeatureFlag flag) {
        if (flag != null && flag.key != null) {
            flags.put(flag.key, flag);
            bumpAtLeast(flag.version);
        }
    }

    public void remove(String key) {
        if (key != null) {
            flags.remove(key);
        }
    }

    private void bumpAtLeast(long version) {
        rulesVersion.updateAndGet(current -> Math.max(current, version));
    }
}
