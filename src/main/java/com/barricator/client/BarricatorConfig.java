package com.barricator.client;

import java.time.Duration;

/**
 * Immutable SDK configuration, created via {@link BarricatorClient#builder(String)}. Sensible
 * production defaults are provided; only the server SDK key is required.
 */
public final class BarricatorConfig {

    private final String sdkKey;
    private final String baseUrl;
    private final Duration connectTimeout;
    private final Duration metricsFlushInterval;
    private final Duration initialReconnectDelay;
    private final Duration maxReconnectDelay;
    private final boolean streamingEnabled;
    private final boolean metricsEnabled;
    private final Duration startupBootstrapTimeout;

    BarricatorConfig(Builder b) {
        this.sdkKey = b.sdkKey;
        this.baseUrl = b.baseUrl;
        this.connectTimeout = b.connectTimeout;
        this.metricsFlushInterval = b.metricsFlushInterval;
        this.initialReconnectDelay = b.initialReconnectDelay;
        this.maxReconnectDelay = b.maxReconnectDelay;
        this.streamingEnabled = b.streamingEnabled;
        this.metricsEnabled = b.metricsEnabled;
        this.startupBootstrapTimeout = b.startupBootstrapTimeout;
    }

    public String sdkKey() {
        return sdkKey;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration metricsFlushInterval() {
        return metricsFlushInterval;
    }

    public Duration initialReconnectDelay() {
        return initialReconnectDelay;
    }

    public Duration maxReconnectDelay() {
        return maxReconnectDelay;
    }

    public boolean streamingEnabled() {
        return streamingEnabled;
    }

    public boolean metricsEnabled() {
        return metricsEnabled;
    }

    public Duration startupBootstrapTimeout() {
        return startupBootstrapTimeout;
    }

    public static final class Builder {
        private final String sdkKey;
        private String baseUrl = "https://app.barricator.io";
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration metricsFlushInterval = Duration.ofSeconds(30);
        private Duration initialReconnectDelay = Duration.ofSeconds(1);
        private Duration maxReconnectDelay = Duration.ofSeconds(60);
        private boolean streamingEnabled = true;
        private boolean metricsEnabled = true;
        private Duration startupBootstrapTimeout = Duration.ofSeconds(5);

        Builder(String sdkKey) {
            if (sdkKey == null || sdkKey.isBlank()) {
                throw new IllegalArgumentException("Barricator SDK key is required");
            }
            this.sdkKey = sdkKey;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = stripTrailingSlash(baseUrl);
            return this;
        }

        public Builder connectTimeout(Duration d) {
            this.connectTimeout = d;
            return this;
        }

        public Builder metricsFlushInterval(Duration d) {
            this.metricsFlushInterval = d;
            return this;
        }

        public Builder initialReconnectDelay(Duration d) {
            this.initialReconnectDelay = d;
            return this;
        }

        public Builder maxReconnectDelay(Duration d) {
            this.maxReconnectDelay = d;
            return this;
        }

        public Builder streamingEnabled(boolean enabled) {
            this.streamingEnabled = enabled;
            return this;
        }

        public Builder metricsEnabled(boolean enabled) {
            this.metricsEnabled = enabled;
            return this;
        }

        public Builder startupBootstrapTimeout(Duration d) {
            this.startupBootstrapTimeout = d;
            return this;
        }

        /** Builds the config only (useful for tests). */
        public BarricatorConfig buildConfig() {
            return new BarricatorConfig(this);
        }

        /** Builds the config and starts a fully-initialized {@link BarricatorClient}. */
        public BarricatorClient build() {
            return BarricatorClient.create(buildConfig());
        }

        private static String stripTrailingSlash(String s) {
            return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
        }
    }
}
