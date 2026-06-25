package com.barricador.client;

import com.barricador.client.internal.EvaluationEngine;
import com.barricador.client.internal.EvaluationResult;
import com.barricador.client.internal.MurmurHash3;
import com.barricador.client.model.FlagModels.Clause;
import com.barricador.client.model.FlagModels.FeatureFlag;
import com.barricador.client.model.FlagModels.Rollout;
import com.barricador.client.model.FlagModels.Rule;
import com.barricador.client.model.FlagModels.Variation;
import com.barricador.client.model.FlagModels.WeightedVariation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationEngineTest {

    private final EvaluationEngine engine = new EvaluationEngine();

    private Variation variation(String id, Object value) {
        Variation v = new Variation();
        v.id = id;
        v.value = value;
        return v;
    }

    @Test
    void offFlagServesOffVariation() {
        FeatureFlag flag = new FeatureFlag();
        flag.key = "f1";
        flag.on = false;
        flag.offVariationId = "off";
        flag.variations = List.of(variation("on", true), variation("off", false));

        EvaluationResult result = engine.evaluate(flag, UserContext.builder("u1").build(), true);
        assertEquals(false, result.value());
        assertEquals(EvaluationResult.REASON_OFF, result.reason());
    }

    @Test
    void ruleClauseMatchesEnterpriseEmail() {
        Clause clause = new Clause();
        clause.attribute = "email";
        clause.op = "ENDS_WITH";
        clause.values = List.of("@enterprise.com");

        Rule rule = new Rule();
        rule.id = "r1";
        rule.clauses = List.of(clause);
        rule.variationId = "on";

        FeatureFlag flag = new FeatureFlag();
        flag.key = "premium";
        flag.on = true;
        flag.variations = List.of(variation("on", true), variation("off", false));
        flag.rules = List.of(rule);
        flag.fallthroughVariationId = "off";

        UserContext enterprise = UserContext.builder("u1").email("user@enterprise.com").build();
        UserContext consumer = UserContext.builder("u2").email("user@gmail.com").build();

        assertEquals(true, engine.evaluate(flag, enterprise, false).value());
        assertEquals(false, engine.evaluate(flag, consumer, false).value());
    }

    @Test
    void missingFlagReturnsFallback() {
        EvaluationResult result = engine.evaluate(null, UserContext.builder("u1").build(), "fallback");
        assertEquals("fallback", result.value());
        assertTrue(result.isDefaulted());
    }

    @Test
    void rolloutIsDeterministicAndStable() {
        WeightedVariation wOn = new WeightedVariation();
        wOn.variationId = "on";
        wOn.weight = 50_000;
        WeightedVariation wOff = new WeightedVariation();
        wOff.variationId = "off";
        wOff.weight = 50_000;

        Rollout rollout = new Rollout();
        rollout.variations = List.of(wOn, wOff);

        FeatureFlag flag = new FeatureFlag();
        flag.key = "rollout-flag";
        flag.on = true;
        flag.variations = List.of(variation("on", true), variation("off", false));
        flag.fallthroughRollout = rollout;

        UserContext user = UserContext.builder("stable-user-123").build();
        Object first = engine.evaluate(flag, user, false).value();
        Object second = engine.evaluate(flag, user, false).value();
        assertEquals(first, second, "Same user must bucket identically across evaluations");
    }

    @Test
    void murmurHashBucketsAreInRange() {
        int b = MurmurHash3.bucket0to99("flag", "salt", "user-1");
        assertTrue(b >= 0 && b < 100);
        assertEquals(MurmurHash3.bucket0to99("flag", "salt", "user-1"),
                MurmurHash3.bucket0to99("flag", "salt", "user-1"));
        assertFalse(false);
    }
}
