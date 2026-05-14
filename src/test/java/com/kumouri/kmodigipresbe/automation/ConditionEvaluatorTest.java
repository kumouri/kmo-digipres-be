package com.kumouri.kmodigipresbe.automation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionEvaluatorTest {

    @Test
    void emptyConditions_matchesAnything() {
        assertThat(ConditionEvaluator.matches(List.of(), Map.of("anything", "yes"))).isTrue();
        assertThat(ConditionEvaluator.matches(null, Map.of())).isTrue();
    }

    @Test
    void equalsOnString_matchesByStringValue() {
        RuleCondition c = RuleCondition.builder()
                .field("toStage").op(RuleCondition.Op.EQUALS).value("WON").build();
        assertThat(ConditionEvaluator.matches(List.of(c), Map.of("toStage", "WON"))).isTrue();
        assertThat(ConditionEvaluator.matches(List.of(c), Map.of("toStage", "LOST"))).isFalse();
    }

    @Test
    void greaterThan_appliesNumericComparison() {
        RuleCondition c = RuleCondition.builder()
                .field("value").op(RuleCondition.Op.GREATER_THAN).value(1000).build();
        assertThat(ConditionEvaluator.matches(List.of(c), Map.of("value", 2500))).isTrue();
        assertThat(ConditionEvaluator.matches(List.of(c), Map.of("value", 500))).isFalse();
        assertThat(ConditionEvaluator.matches(List.of(c), Map.of("value", 1000))).isFalse();
    }

    @Test
    void exists_isFalseOnMissingOrNull() {
        RuleCondition c = RuleCondition.builder()
                .field("ownerId").op(RuleCondition.Op.EXISTS).build();
        assertThat(ConditionEvaluator.matches(List.of(c), Map.of("ownerId", "abc"))).isTrue();
        assertThat(ConditionEvaluator.matches(List.of(c), Map.of())).isFalse();
    }

    @Test
    void andSemantics_allMustMatch() {
        List<RuleCondition> all = List.of(
                RuleCondition.builder().field("toStage")
                        .op(RuleCondition.Op.EQUALS).value("WON").build(),
                RuleCondition.builder().field("value")
                        .op(RuleCondition.Op.GREATER_THAN).value(1000).build());
        assertThat(ConditionEvaluator.matches(all, Map.of("toStage", "WON", "value", 5000))).isTrue();
        assertThat(ConditionEvaluator.matches(all, Map.of("toStage", "WON", "value", 500))).isFalse();
        assertThat(ConditionEvaluator.matches(all, Map.of("toStage", "LOST", "value", 5000))).isFalse();
    }
}
