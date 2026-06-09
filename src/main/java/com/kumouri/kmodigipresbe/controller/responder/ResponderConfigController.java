package com.kumouri.kmodigipresbe.controller.responder;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.model.idempotency.IdempotentRoute;
import com.kumouri.kmodigipresbe.model.responder.IntentClassification;
import com.kumouri.kmodigipresbe.model.responder.IntentDefinition;
import com.kumouri.kmodigipresbe.model.responder.ResponderConfig;
import com.kumouri.kmodigipresbe.module.responder.ResponderAutoConfiguration;
import com.kumouri.kmodigipresbe.repository.responder.ResponderConfigRepository;
import com.kumouri.kmodigipresbe.service.responder.InboundIntentClassifier;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Admin surface for the E2 Inbound Responder + Intent Router — per-tenant config read/upsert + an
 * admin dry-run classify (the {@code NurtureCampaignController} precedent).
 *
 * <h2>Gating</h2>
 * <ul>
 *   <li>{@code @ConditionalOnProperty(prefix="kmosf.modules.responder", name="enabled",
 *       matchIfMissing=true)} — present by default; absent from the OpenAPI spec only when the module is
 *       explicitly disabled (it 404s, the module-gate precedent).</li>
 *   <li>per-tenant membership via {@link TenantModuleRegistry#requireEnabled(String)} (1130/1131/1132).</li>
 *   <li>{@link RoleGuard#requireRole "ADMIN"} on every endpoint (1800 otherwise).</li>
 * </ul>
 * Base path {@code /api/v1} (via {@code spring.webflux.base-path}), so these map to
 * {@code /api/v1/responder/config...}. The inbound webhook itself is the existing
 * {@code TwilioInboundSmsController} under {@code /public/**} (REUSED — no new public surface here).
 *
 * <h2>Errors (4320-4339 band)</h2>
 * {@code 4320} responder not enabled for the tenant (the in-range parity echo; the real gate is the
 * {@code requireEnabled} 1130/1132); {@code 4321} config not found on a read; {@code 4322} invalid
 * config (an intent with a blank name).
 */
@RestController
@RequestMapping("/responder/config")
@ConditionalOnProperty(prefix = "kmosf.modules.responder", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class ResponderConfigController {

    private final ResponderConfigRepository configs;
    private final InboundIntentClassifier classifier;
    private final TenantModuleRegistry modules;

    /** Read the tenant's responder config (4321 if none has been created yet). */
    @GetMapping
    public Mono<ResponderConfig> get() {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> configs.findByTenantId(ctx.tenantId())
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Responder config not found for this tenant", 4321, 404))));
    }

    /**
     * Upsert the tenant's responder config (one row per tenant). Validates each intent has a non-blank
     * name (4322). Explicit-boolean find-then-update (never {@code switchIfEmpty(create)}): an existing
     * row is updated in place (preserving id/version/timestamps), else a fresh row is created.
     */
    @PutMapping
    public Mono<ResponderConfig> upsert(@RequestBody ConfigRequest body) {
        validate(body);
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> configs.findByTenantId(ctx.tenantId())
                        .map(java.util.Optional::of)
                        .defaultIfEmpty(java.util.Optional.empty())
                        .flatMap(existing -> {
                            ResponderConfig toSave = existing.isPresent()
                                    ? body.applyTo(existing.get())
                                    : body.toNewEntity(ctx.tenantId());
                            return configs.save(toSave);
                        }));
    }

    /**
     * Admin dry-run: classify a pasted message against the tenant's configured intents (no SMS, no
     * conversation state) — a config-tuning aid. {@code @IdempotentRoute} (it is a POST; returns a
     * non-empty body per the response-tee requirement). Best-effort: a classifier failure surfaces as
     * {@link IntentClassification#unknown()} (the classifier never throws).
     */
    @PostMapping("/test-classify")
    @IdempotentRoute
    public Mono<IntentClassification> testClassify(@RequestBody TestClassifyRequest body) {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> configs.findByTenantId(ctx.tenantId())
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Responder config not found for this tenant", 4321, 404)))
                        .flatMap(cfg -> classifier.classify(
                                body.message(), cfg.getIntents(), cfg.getModel(),
                                cfg.getSystemPromptOverride())));
    }

    /** responder module loaded + enabled for the tenant, then ADMIN. */
    private Mono<Void> guard() {
        return modules.requireEnabled(ResponderAutoConfiguration.MODULE_KEY)
                .then(RoleGuard.requireRole("ADMIN"));
    }

    private static void validate(ConfigRequest body) {
        if (body.intents() != null) {
            for (IntentDefinition d : body.intents()) {
                if (d == null || d.name() == null || d.name().isBlank()) {
                    throw new DigiPresBeException(
                            "Responder config intent requires a non-blank name", 4322, 400);
                }
            }
        }
    }

    /**
     * The config upsert request — drops server-managed fields (id/tenantId/version/timestamps).
     *
     * @param enabled                  master switch (defaults true on create when null)
     * @param vertical                 the vertical key gating eligible handlers
     * @param intents                  the configured intents the classifier may emit
     * @param replyCapPerContactPerDay outbound reply cap per sender per rolling day (defaults 5)
     * @param fallbackHandlerKey       optional non-default fallback handler key
     * @param model                    optional classifier model override
     * @param systemPromptOverride     optional classifier system-prompt override
     */
    public record ConfigRequest(
            Boolean enabled,
            String vertical,
            List<IntentDefinition> intents,
            Integer replyCapPerContactPerDay,
            String fallbackHandlerKey,
            String model,
            String systemPromptOverride) {

        ResponderConfig toNewEntity(java.util.UUID tenantId) {
            return ResponderConfig.builder()
                    .tenantId(tenantId)
                    .enabled(enabled == null || enabled)
                    .vertical(vertical)
                    .intents(intents == null ? new ArrayList<>() : new ArrayList<>(intents))
                    .replyCapPerContactPerDay(replyCapPerContactPerDay == null ? 5 : replyCapPerContactPerDay)
                    .fallbackHandlerKey(fallbackHandlerKey)
                    .model(model)
                    .systemPromptOverride(systemPromptOverride)
                    .build();
        }

        ResponderConfig applyTo(ResponderConfig existing) {
            return existing.toBuilder()
                    .enabled(enabled == null ? existing.isEnabled() : enabled)
                    .vertical(vertical != null ? vertical : existing.getVertical())
                    .intents(intents == null ? new ArrayList<>() : new ArrayList<>(intents))
                    .replyCapPerContactPerDay(replyCapPerContactPerDay == null
                            ? existing.getReplyCapPerContactPerDay() : replyCapPerContactPerDay)
                    .fallbackHandlerKey(fallbackHandlerKey)
                    .model(model)
                    .systemPromptOverride(systemPromptOverride)
                    .build();
        }
    }

    /** The dry-run classify request body. */
    public record TestClassifyRequest(String message) {
    }
}
