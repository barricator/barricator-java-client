package com.barricador.client.internal;

import com.barricador.client.UserContext;
import com.barricador.client.model.FlagModels.Clause;
import com.barricador.client.model.FlagModels.FeatureFlag;
import com.barricador.client.model.FlagModels.Rollout;
import com.barricador.client.model.FlagModels.Rule;
import com.barricador.client.model.FlagModels.Variation;
import com.barricador.client.model.FlagModels.WeightedVariation;

import java.util.List;
import java.util.Optional;

/**
 * Pure, synchronous, in-memory targeting evaluator — the heart of the zero-latency guarantee. It
 * NEVER performs I/O. The algorithm is a faithful port of the backend {@code EvaluationEngine} so
 * local (server-SDK) results match server-side (client-SDK) results for the same user + ruleset.
 */
public final class EvaluationEngine {

    public EvaluationResult evaluate(FeatureFlag flag, UserContext ctx, Object fallback) {
        if (flag == null) {
            return new EvaluationResult(fallback, null, EvaluationResult.REASON_FLAG_NOT_FOUND);
        }
        try {
            if (!flag.on) {
                return served(flag, flag.offVariationId, EvaluationResult.REASON_OFF, fallback);
            }
            if (flag.rules != null) {
                for (Rule rule : flag.rules) {
                    if (ruleMatches(rule, ctx)) {
                        String variationId = rule.rollout != null
                                ? bucket(flag.key, rule.rollout, ctx)
                                : rule.variationId;
                        return served(flag, variationId, EvaluationResult.REASON_RULE_MATCH, fallback);
                    }
                }
            }
            if (flag.fallthroughRollout != null) {
                String variationId = bucket(flag.key, flag.fallthroughRollout, ctx);
                return served(flag, variationId, EvaluationResult.REASON_FALLTHROUGH, fallback);
            }
            return served(flag, flag.fallthroughVariationId, EvaluationResult.REASON_FALLTHROUGH, fallback);
        } catch (RuntimeException e) {
            return new EvaluationResult(fallback, null, EvaluationResult.REASON_ERROR);
        }
    }

    private EvaluationResult served(FeatureFlag flag, String variationId, String reason, Object fallback) {
        Object value = variationValue(flag, variationId)
                .orElse(flag.defaultValue != null ? flag.defaultValue : fallback);
        return new EvaluationResult(value, variationId, reason);
    }

    private Optional<Object> variationValue(FeatureFlag flag, String variationId) {
        if (variationId == null || flag.variations == null) {
            return Optional.empty();
        }
        return flag.variations.stream()
                .filter(v -> variationId.equals(v.id))
                .map(v -> v.value)
                .findFirst();
    }

    private String bucket(String flagKey, Rollout rollout, UserContext ctx) {
        List<WeightedVariation> weighted = rollout.variations;
        if (weighted == null || weighted.isEmpty()) {
            return null;
        }
        String bucketBy = rollout.bucketBy == null ? "key" : rollout.bucketBy;
        String value = ctx.attribute(bucketBy).map(Object::toString).orElse(ctx.key());
        int b = MurmurHash3.bucket100k(flagKey, rollout.salt, value);
        int cumulative = 0;
        for (WeightedVariation wv : weighted) {
            cumulative += wv.weight;
            if (b < cumulative) {
                return wv.variationId;
            }
        }
        return weighted.get(weighted.size() - 1).variationId;
    }

    private boolean ruleMatches(Rule rule, UserContext ctx) {
        if (rule.clauses == null || rule.clauses.isEmpty()) {
            return false;
        }
        for (Clause clause : rule.clauses) {
            if (!clauseMatches(clause, ctx)) {
                return false;
            }
        }
        return true;
    }

    private boolean clauseMatches(Clause clause, UserContext ctx) {
        Optional<Object> attr = ctx.attribute(clause.attribute);
        boolean result = attr.isPresent() && evaluateOperator(clause.op, attr.get(), clause.values);
        return clause.negate != result;
    }

    private boolean evaluateOperator(String op, Object attr, List<Object> values) {
        if (op == null || values == null) {
            return false;
        }
        return switch (op) {
            case "IN" -> values.stream().anyMatch(v -> looseEquals(attr, v));
            case "NOT_IN" -> values.stream().noneMatch(v -> looseEquals(attr, v));
            case "EQUALS" -> !values.isEmpty() && looseEquals(attr, values.get(0));
            case "NOT_EQUALS" -> values.isEmpty() || !looseEquals(attr, values.get(0));
            case "CONTAINS" -> values.stream().anyMatch(v -> str(attr).contains(str(v)));
            case "NOT_CONTAINS" -> values.stream().noneMatch(v -> str(attr).contains(str(v)));
            case "STARTS_WITH" -> values.stream().anyMatch(v -> str(attr).startsWith(str(v)));
            case "ENDS_WITH" -> values.stream().anyMatch(v -> str(attr).endsWith(str(v)));
            case "MATCHES_REGEX" -> values.stream().anyMatch(v -> safeRegex(str(attr), str(v)));
            case "GREATER_THAN" -> cmpNum(attr, values) > 0;
            case "GREATER_THAN_OR_EQUAL" -> cmpNum(attr, values) >= 0;
            case "LESS_THAN" -> cmpNum(attr, values) < 0;
            case "LESS_THAN_OR_EQUAL" -> cmpNum(attr, values) <= 0;
            case "SEMVER_EQUAL" -> cmpSemver(attr, values) == 0;
            case "SEMVER_GREATER_THAN" -> cmpSemver(attr, values) > 0;
            case "SEMVER_LESS_THAN" -> cmpSemver(attr, values) < 0;
            case "BEFORE" -> cmpNum(attr, values) < 0;
            case "AFTER" -> cmpNum(attr, values) > 0;
            default -> false;
        };
    }

    private boolean looseEquals(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return na.doubleValue() == nb.doubleValue();
        }
        return a.equals(b) || a.toString().equals(b.toString());
    }

    private String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private boolean safeRegex(String input, String pattern) {
        try {
            return java.util.regex.Pattern.compile(pattern).matcher(input).find();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private int cmpNum(Object attr, List<Object> values) {
        if (values.isEmpty()) {
            return 0;
        }
        try {
            return Double.compare(Double.parseDouble(attr.toString()),
                    Double.parseDouble(values.get(0).toString()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int cmpSemver(Object attr, List<Object> values) {
        if (values.isEmpty()) {
            return 0;
        }
        int[] a = parseSemver(attr.toString());
        int[] b = parseSemver(values.get(0).toString());
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(a[i], b[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private int[] parseSemver(String v) {
        int[] parts = {0, 0, 0};
        String[] split = v.split("[-+]")[0].split("\\.");
        for (int i = 0; i < Math.min(3, split.length); i++) {
            try {
                parts[i] = Integer.parseInt(split[i].trim());
            } catch (NumberFormatException ignored) {
                parts[i] = 0;
            }
        }
        return parts;
    }
}
