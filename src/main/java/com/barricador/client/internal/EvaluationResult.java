package com.barricador.client.internal;

/** Outcome of a local flag evaluation: the resolved value, the variation id, and the reason. */
public record EvaluationResult(Object value, String variationId, String reason) {

    public static final String REASON_OFF = "OFF";
    public static final String REASON_RULE_MATCH = "RULE_MATCH";
    public static final String REASON_FALLTHROUGH = "FALLTHROUGH";
    public static final String REASON_FLAG_NOT_FOUND = "FLAG_NOT_FOUND";
    public static final String REASON_ERROR = "ERROR";

    public boolean isDefaulted() {
        return REASON_FLAG_NOT_FOUND.equals(reason) || REASON_ERROR.equals(reason);
    }
}
