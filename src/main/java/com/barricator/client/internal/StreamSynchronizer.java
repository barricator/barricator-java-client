package com.barricator.client.internal;

import com.barricator.client.model.FlagModels.FeatureFlag;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Maintains the persistent SSE connection to {@code /api/v1/flags/stream} on a dedicated daemon
 * thread, applying deltas to the {@link FlagStore} as they arrive. On any disconnect it reconnects
 * with exponential backoff + jitter, and keeps serving the last cached state in the meantime — it
 * never throws into the host application or blocks evaluation.
 */
public final class StreamSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(StreamSynchronizer.class);

    private final HttpTransport transport;
    private final FlagStore store;
    private final ObjectMapper mapper;
    private final Runnable onResync;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread thread;

    public StreamSynchronizer(HttpTransport transport, FlagStore store, ObjectMapper mapper, Runnable onResync) {
        this.transport = transport;
        this.store = store;
        this.mapper = mapper;
        this.onResync = onResync;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            thread = new Thread(this::runLoop, "barricator-sse");
            thread.setDaemon(true);
            thread.start();
        }
    }

    public void stop() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void runLoop() {
        Duration delay = transport.config().initialReconnectDelay();
        while (running.get()) {
            try {
                connectAndConsume();
                // Clean end of stream → reset backoff before reconnecting.
                delay = transport.config().initialReconnectDelay();
            } catch (Exception e) {
                if (!running.get()) {
                    break;
                }
                log.debug("SSE stream disconnected ({}); reconnecting in {}", e.getMessage(), delay);
                sleep(withJitter(delay));
                delay = nextBackoff(delay);
            }
        }
    }

    private void connectAndConsume() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(transport.config().baseUrl() + "/api/v1/flags/stream"))
                .header("Authorization", "Bearer " + transport.config().sdkKey())
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofMinutes(30))
                .GET()
                .build();

        HttpResponse<Stream<String>> response = transport.httpClient()
                .send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("SSE connect failed: HTTP " + response.statusCode());
        }
        // A successful (re)connection: re-bootstrap so we don't miss deltas dropped while offline.
        onResync.run();

        String currentEvent = null;
        StringBuilder data = new StringBuilder();
        try (Stream<String> lines = response.body()) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (!running.get()) {
                    break;
                }
                if (line.isEmpty()) {
                    dispatch(currentEvent, data.toString());
                    currentEvent = null;
                    data.setLength(0);
                } else if (line.startsWith(":")) {
                    // comment / heartbeat — ignore
                } else if (line.startsWith("event:")) {
                    currentEvent = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                }
            }
        }
    }

    private void dispatch(String event, String data) {
        if (event == null || data.isEmpty()) {
            return;
        }
        try {
            if ("flag-change".equals(event)) {
                JsonNode node = mapper.readTree(data);
                String type = node.path("type").asText("PUT");
                if ("DELETE".equals(type)) {
                    store.remove(node.path("flagKey").asText(null));
                } else {
                    JsonNode flagNode = node.get("flag");
                    if (flagNode != null && !flagNode.isNull()) {
                        store.upsert(mapper.treeToValue(flagNode, FeatureFlag.class));
                    }
                }
            }
            // "connected" / "heartbeat" events need no action.
        } catch (Exception e) {
            log.debug("Failed to apply SSE delta: {}", e.getMessage());
        }
    }

    private Duration nextBackoff(Duration current) {
        Duration doubled = current.multipliedBy(2);
        Duration max = transport.config().maxReconnectDelay();
        return doubled.compareTo(max) > 0 ? max : doubled;
    }

    private Duration withJitter(Duration base) {
        long millis = base.toMillis();
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, millis / 2));
        return Duration.ofMillis(millis + jitter);
    }

    private void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
