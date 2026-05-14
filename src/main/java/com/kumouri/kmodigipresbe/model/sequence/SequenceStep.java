package com.kumouri.kmodigipresbe.model.sequence;

import com.kumouri.kmodigipresbe.automation.RuleCondition;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Embedded element of {@link Sequence#getSteps()}. Step semantics live on
 * {@link SequenceStepType}.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SequenceStep {

    private int stepIndex;

    private SequenceStepType type;

    /** ISO-8601 duration (e.g. {@code "P3D"}, {@code "PT1H"}). WAIT only. */
    private String waitDuration;

    /** Subject for EMAIL_SEND. Plain string; Phase 4 templating lands later if needed. */
    private String emailSubject;

    /** HTML body for EMAIL_SEND. */
    private String emailHtmlBody;

    /** Text body for EMAIL_SEND. */
    private String emailTextBody;

    /** Postmark tag for EMAIL_SEND (groups sends in Postmark's UI / engagement reports). */
    private String emailTag;

    /** EMAIL_SEND from-address override. Null = use the configured sequence default. */
    private String emailFrom;

    /** BRANCH-only: predicates evaluated against the enrollment's engagement payload. */
    private List<RuleCondition> branchCondition;

    /** BRANCH-only: step index to advance to when the condition matches. */
    private Integer branchTrueNextStep;

    /** BRANCH-only: step index to advance to when the condition does not match. */
    private Integer branchFalseNextStep;
}
