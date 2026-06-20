package com.barricator.client.internal;

import com.barricator.client.BarricatorConfig;
import com.barricator.client.model.FlagModels.BootstrapResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTP layer over the JDK {@link HttpClient} for the two request/response calls: the startup
 * bootstrap and the periodic metrics flush. The long-lived SSE stream is handled by
 * {@link StreamSynchronizer}, which shares this client.
 */
public final class HttpTransport {

    private final BarricatorConfig config;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public HttpTransport(BarricatorConfig config, ObjectMapper mapper, HttpClient httpClient) {
        this.config = config;
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    HttpClient httpClient() {
        return httpClient;
    }

    BarricatorConfig config() {
        return config;
    }

    /** Fetches the full environment ruleset. Throws on any non-2xx or transport failure. */
    public BootstrapResponse bootstrap() throws Exception {
        HttpRequest request = baseRequest("/api/v1/flags/bootstrap")
                .timeout(config.startupBootstrapTimeout())
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Bootstrap failed: HTTP " + response.statusCode());
        }
        return mapper.readValue(response.body(), BootstrapResponse.class);
    }

    /** Posts a batch of aggregated metrics. Returns true on success (failures are swallowed by caller). */
    public boolean flushMetrics(List<MetricsBuffer.MetricEvent> events) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("events", events);
        HttpRequest request = baseRequest("/api/v1/metrics/flush")
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        return response.statusCode() / 100 == 2;
    }

    HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
                .header("Authorization", "Bearer " + config.sdkKey())
                .header("Accept", "application/json");
    }
}
