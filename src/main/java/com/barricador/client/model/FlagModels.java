package com.barricador.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Wire-format models for the flag ruleset downloaded from {@code /api/v1/flags/bootstrap} and pushed
 * over the SSE stream. These intentionally mirror the backend's {@code FeatureFlag} shape so the
 * local {@code EvaluationEngine} can reproduce server-side targeting exactly. Unknown fields are
 * ignored for forward compatibility.
 */
public final class FlagModels {

    private FlagModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class FeatureFlag {
        public String key;
        public String type;
        public List<Variation> variations;
        public Object defaultValue;
        public boolean on;
        public String offVariationId;
        public List<Rule> rules;
        public String fallthroughVariationId;
        public Rollout fallthroughRollout;
        public long version;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Variation {
        public String id;
        public Object value;
        public String name;
        public String description;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Rule {
        public String id;
        public String description;
        public List<Clause> clauses;
        public String variationId;
        public Rollout rollout;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Clause {
        public String attribute;
        public String op;
        public List<Object> values;
        public boolean negate;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Rollout {
        public String bucketBy;
        public String salt;
        public List<WeightedVariation> variations;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class WeightedVariation {
        public String variationId;
        public int weight;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class BootstrapResponse {
        public String environmentId;
        public long rulesVersion;
        public List<FeatureFlag> flags;
    }
}
