package com.kumouri.kmodigipresbe.model.sequence;

/**
 * Step types supported by {@code SequenceEngine}.
 * <ul>
 *   <li>{@code EMAIL_SEND} — render and send a transactional email. The send's
 *       MessageID is recorded on the enrollment so subsequent {@code BRANCH}
 *       steps can correlate engagement events.</li>
 *   <li>{@code WAIT} — pause for {@code waitDuration} before advancing.</li>
 *   <li>{@code BRANCH} — evaluate {@code branchCondition} against the enrollment's
 *       last engagement event ({@code OPEN}/{@code CLICK}/...); on match advance
 *       to {@code branchTrueNextStep}, otherwise {@code branchFalseNextStep}.</li>
 *   <li>{@code EXIT} — mark enrollment {@code COMPLETED}.</li>
 * </ul>
 */
public enum SequenceStepType {
    EMAIL_SEND,
    WAIT,
    BRANCH,
    EXIT
}
