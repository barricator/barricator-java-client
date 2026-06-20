package com.barricator.client;

import com.barricator.client.internal.EvaluationEngine;
import com.barricator.client.internal.EvaluationResult;
import com.barricator.client.internal.FlagStore;
import com.barricator.client.internal.HttpTransport;
import com.barricator.client.internal.MetricsBuffer;
import com.barricator.client.internal.StreamSynchronizer;
import com.barricator.client.model.FlagModels.BootstrapResponse;
import com.barricator.client.model.FlagModels.FeatureFlag;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Barricator Java Server SDK entrypoint. Build one per process via
 * {@code BarricatorClient.builder(sdkKey).build()} and share it.
 *
 * <h2>Zero-impact guarantee</h2>
 * Evaluation ({@link #isEnabled}, {@link #stringVariation}, …) is a synchronous in-memory lookup and
 * never performs network I/O. State is populated by an async bootstrap and kept fresh by a background
 * SSE stream; telemetry is aggregated in memory and flushed by a background worker. Any backend
 * outage degrades gracefully to the last cached ruleset, then to the caller-supplied default.
 */
public final class BarricatorClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BarricatorClient.class);

    private final BarricatorConfig config;
    private final FlagStore store = new FlagStore();
    private final EvaluationEngine engine = new EvaluationEngine();
    private final MetricsBuffer metrics = new MetricsBuffer();
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpTransport transport;
    private final StreamSynchronizer synchronizer;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private BarricatorClient(BarricatorConfig config) {
        this.config = config;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .build();
        this.transport = new HttpTransport(config, mapper, httpClient);
        this.synchronizer = new StreamSynchronizer(transport, store, mapper, this::safeBootstrap);
        this.scheduler = Executors.newScheduledThreadPool(1, daemonFactory());
        start();
    }

    public static BarricatorConfig.Builder builder(String sdkKey) {
        return new BarricatorConfig.Builder(sdkKey);
    }

    /** Package-visible bridge so {@code BarricatorConfig.Builder.build()-style} usage stays fluent. */
    public static BarricatorClient create(BarricatorConfig config) {
        return new BarricatorClient(config);
    }

    private void start() {
        // Best-effort synchronous bootstrap so flags are ready ASAP; failure falls back to defaults.
        safeBootstrap();
        if (config.streamingEnabled()) {
            synchronizer.start();
        }
        if (config.metricsEnabled()) {
            long ms = config.metricsFlushInterval().toMillis();
            scheduler.scheduleAtFixedRate(this::safeFlush, ms, ms, TimeUnit.MILLISECONDS);
        }
    }

    // --- Public evaluation API (synchronous, in-memory, never throws) ---

    public boolean isEnabled(String flagKey, UserContext user) {
        return boolVariation(flagKey, user, false);
    }

    public boolean boolVariation(String flagKey, UserContext user, boolean defaultValue) {
        Object v = evaluate(flagKey, user, defaultValue).value();
        return v instanceof Boolean b ? b : defaultValue;
    }

    public String stringVariation(String flagKey, UserContext user, String defaultValue) {
        Object v = evaluate(flagKey, user, defaultValue).value();
        return v == null ? defaultValue : v.toString();
    }

    public double numberVariation(String flagKey, UserContext user, double defaultValue) {
        Object v = evaluate(flagKey, user, defaultValue).value();
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return v == null ? defaultValue : Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Returns the raw JSON value (Map/List/primitive) for a JSON flag, or the default. */
    public Object jsonVariation(String flagKey, UserContext user, Object defaultValue) {
        return evaluate(flagKey, user, defaultValue).value();
    }

    private EvaluationResult evaluate(String flagKey, UserContext user, Object fallback) {
        FeatureFlag flag = store.get(flagKey);
        EvaluationResult result = engine.evaluate(flag, user, fallback);
        if (config.metricsEnabled()) {
            metrics.record(flagKey, result.variationId(), result.isDefaulted());
        }
        return result;
    }

    public boolean isInitialized() {
        return store.isInitialized();
    }

    // --- Lifecycle ---

    private void safeBootstrap() {
        try {
            BootstrapResponse resp = transport.bootstrap();
            Map<String, FeatureFlag> map = new HashMap<>();
            if (resp.flags != null) {
                resp.flags.forEach(f -> map.put(f.key, f));
            }
            store.replaceAll(map, resp.rulesVersion);
            log.debug("Barricator bootstrap complete: {} flags (v{})", map.size(), resp.rulesVersion);
        } catch (Exception e) {
            // Never fatal: keep serving cached state (or defaults if first bootstrap failed).
            log.warn("Barricator bootstrap failed ({}); serving {} cached flags / defaults",
                    e.getMessage(), store.isInitialized() ? "last" : "no");
        }
    }

    private void safeFlush() {
        try {
            List<MetricsBuffer.MetricEvent> events = metrics.drain();
            if (!events.isEmpty()) {
                transport.flushMetrics(events);
            }
        } catch (Exception e) {
            log.debug("Metrics flush failed: {}", e.getMessage());
        }
    }

    /** Forces an immediate metrics flush (also invoked on close). */
    public void flush() {
        safeFlush();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            synchronizer.stop();
            safeFlush();
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private ThreadFactory daemonFactory() {
        return r -> {
            Thread t = new Thread(r, "barricator-metrics");
            t.setDaemon(true);
            return t;
        };
    }
}
