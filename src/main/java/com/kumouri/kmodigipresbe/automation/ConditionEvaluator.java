package com.kumouri.kmodigipresbe.automation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stateless evaluation of {@link RuleCondition} lists against a {@link DomainEvent}'s
 * payload. AND across conditions; OR is expressed by writing multiple rules.
 */
public final class ConditionEvaluator {

    private ConditionEvaluator() {
    }

    public static boolean matches(List<RuleCondition> conditions, Map<String, Object> payload) {
        if (conditions == null || conditions.isEmpty()) return true;
        for (RuleCondition c : conditions) {
            if (!matches(c, payload)) return false;
        }
        return true;
    }

    private static boolean matches(RuleCondition c, Map<String, Object> payload) {
        Object actual = payload == null ? null : payload.get(c.getField());
        return switch (c.getOp()) {
            case EQUALS -> Objects.equals(stringify(actual), stringify(c.getValue()));
            case NOT_EQUALS -> !Objects.equals(stringify(actual), stringify(c.getValue()));
            case EXISTS -> actual != null;
            case NOT_EXISTS -> actual == null;
            case GREATER_THAN -> compare(actual, c.getValue()) > 0;
            case LESS_THAN -> compare(actual, c.getValue()) < 0;
        };
    }

    private static String stringify(Object o) {
        return o == null ? null : o.toString();
    }

    private static int compare(Object actual, Object expected) {
        BigDecimal a = toBig(actual);
        BigDecimal b = toBig(expected);
        if (a == null || b == null) return 0;
        return a.compareTo(b);
    }

    private static BigDecimal toBig(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
