package com.kumouri.kmodigipresbe.automation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * One side-effect to perform when a rule fires. {@code type} is one of:
 * <ul>
 *   <li>{@code SEND_EMAIL_TEMPLATE} — {@code params.templateName}, {@code params.toContactIdField}
 *       (a payload field whose value is the recipient contact id).</li>
 *   <li>{@code CREATE_TASK} — {@code params.summary} (Mustache), {@code params.dueAtSeconds}
 *       (delay relative to event time, default 0).</li>
 *   <li>{@code OUTBOUND_WEBHOOK} — {@code params.subscriptionId} pointing at a
 *       {@code WebhookSubscription} document.</li>
 *   <li>{@code SEND_SMS} (Phase 10e) — {@code params.templateName} (registry key in
 *       {@code SmsTemplateRegistry}; falls back to the literal string as the body when
 *       no matching template is registered), {@code params.toPhoneField} (a payload
 *       field whose value is the recipient phone in E.164 format, e.g. {@code "+15555550100"}).
 *       Missing or non-E.164 recipient values are skipped (no throw, matches the
 *       {@code SEND_EMAIL_TEMPLATE} skip-on-missing behavior).</li>
 * </ul>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class RuleAction {
    private ActionType type;

    @Builder.Default
    private Map<String, Object> params = Map.of();

    public enum ActionType { SEND_EMAIL_TEMPLATE, CREATE_TASK, OUTBOUND_WEBHOOK, SEND_SMS }
}
