package com.kumouri.kmodigipresbe.model.responder;

import com.kumouri.kmodigipresbe.audit.Auditable;
import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * E2 — the per-tenant configuration for the inbound responder / intent router. One row per tenant
 * (unique {@code tenant_idx {tenantId}}); a tenant-scoped CRM entity ({@code Auditable}).
 *
 * <h2>The keystone no-op invariant</h2>
 * <strong>A tenant with no {@code ResponderConfig} row (or {@code enabled=false}, or an empty
 * {@code intents} list) behaves exactly as before this engine existed</strong>: the
 * {@link com.kumouri.kmodigipresbe.service.responder.InboundIntentRouter} treats absent/disabled/empty
 * config as "no handlers, handoff off" and returns {@code IGNORED}, so the shipped inbound-SMS path is
 * byte-identical (proven by a dedicated IT). This is what makes the generic inbound delegation safe to
 * wire on by default — it only activates for a tenant that has explicitly configured a responder.
 *
 * <p>{@code vertical} selects which {@link com.kumouri.kmodigipresbe.service.responder.IntentHandler}s
 * are eligible (a handler's {@code supports(vertical, intent)} gate). {@code intents} is the set the
 * classifier is constrained to. {@code replyCapPerContactPerDay} caps outbound replies per sender per
 * rolling day (TCPA-friendly). {@code model}/{@code systemPromptOverride} optionally override the
 * classifier model + prompt. {@code fallbackHandlerKey} optionally names a non-default handler to use
 * when no specific handler matched (default = the built-in {@code default-handoff}).
 */
@Document("responder_configs")
@CompoundIndex(name = "tenant_idx", def = "{ 'tenantId': 1 }", unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ResponderConfig implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    /** Master switch; when false the router is a no-op for this tenant (= {@code IGNORED}). */
    @Builder.Default
    private boolean enabled = true;

    /** The vertical key gating eligible handlers (e.g. {@code "realestate"}, {@code "health"}, {@code "home"}). */
    private String vertical;

    /** The configured intents the classifier may emit. Empty ⇒ the router is a no-op ({@code IGNORED}). */
    @Builder.Default
    private List<IntentDefinition> intents = new ArrayList<>();

    /** Max outbound replies per sender phone per rolling day (consent/anti-spam). 0 ⇒ never reply. */
    @Builder.Default
    private int replyCapPerContactPerDay = 5;

    /** Optional handler key to use when no specific handler matched (default = the built-in handoff). */
    private String fallbackHandlerKey;

    /** Optional classifier model override (else {@code kmosf.responder.classify-model}). */
    private String model;

    /** Optional classifier system-prompt override (else the built-in strict prompt). */
    private String systemPromptOverride;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
