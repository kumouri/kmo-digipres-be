package com.kumouri.kmodigipresbe.automation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One predicate on a domain event's payload — e.g. {@code field=toStage, op=EQUALS,
 * value="WON"}. Multiple conditions on a rule are ANDed; OR is expressed by creating
 * multiple rules with the same trigger and action.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class RuleCondition {

    /**
     * Dot-path into the event payload. {@code "toStage"} or {@code "value"} for top-level
     * keys; nested paths are not yet supported.
     */
    private String field;

    private Op op;

    private Object value;

    public enum Op {
        EQUALS, NOT_EQUALS, EXISTS, NOT_EXISTS,
        GREATER_THAN, LESS_THAN
    }
}
