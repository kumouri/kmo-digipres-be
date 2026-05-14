package com.kumouri.kmodigipresbe.model.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

/**
 * Either {@code templateId} or {@code templateName} must be supplied. The
 * template is rendered with {@code variables} and sent to the email channel of
 * {@code toContactId}. The contact's first email is used.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SendTemplateRequest {
    private UUID templateId;
    private String templateName;

    @NotNull
    private UUID toContactId;

    /**
     * Override the template's {@code fromAddress}. Falls back to the template's
     * configured from, then to the tenant SMTP username.
     */
    private String fromAddressOverride;

    @Builder.Default
    private Map<String, Object> variables = Map.of();
}
