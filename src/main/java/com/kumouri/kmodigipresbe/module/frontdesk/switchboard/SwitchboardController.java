package com.kumouri.kmodigipresbe.module.frontdesk.switchboard;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.frontdesk.FrontDeskAutoConfiguration;
import com.kumouri.kmodigipresbe.module.responder.ResponderAutoConfiguration;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * T4 (Health "Switchboard AI") — the admin surface: per-tenant logistics-config read/upsert + the
 * deflection-analytics read. The {@code FrontDeskNurtureController} / {@code ResponderConfigController}
 * precedent.
 *
 * <h2>Endpoints (under {@code spring.webflux.base-path=/api/v1})</h2>
 * <ul>
 *   <li>{@code GET  /frontdesk/switchboard/config} → the tenant's {@link SwitchboardConfig} (4391 if none).</li>
 *   <li>{@code PUT  /frontdesk/switchboard/config} (body {@link ConfigRequest}) → the upserted config.</li>
 *   <li>{@code GET  /frontdesk/switchboard/deflection-stats} → {@link SwitchboardDeflectionStats}
 *       (logistics / tripwire / handoff / total / deflectionRate).</li>
 * </ul>
 * The inbound webhook itself is the existing {@code TwilioInboundSmsController} under {@code /public/**}
 * (REUSED — no new public surface here).
 *
 * <h2>Gating (BOTH modules — the {@code FrontDeskNurtureController} precedent)</h2>
 * <ul>
 *   <li>{@code @ConditionalOnProperty(kmosf.modules.frontdesk.enabled)} so the controller is absent from the
 *       generated OpenAPI spec when frontdesk is off ({@code OpenApiEndpointIT} runs without the flag);</li>
 *   <li>per-tenant membership via {@link TenantModuleRegistry#requireEnabled(String)} for <strong>both</strong>
 *       {@code frontdesk} AND {@code responder} (1130/1132 otherwise);</li>
 *   <li>{@link RoleGuard#requireRole "ADMIN"} on every endpoint (1800 otherwise).</li>
 * </ul>
 *
 * <h2>Errors (4390-4399 band)</h2>
 * {@code 4391} switchboard config not found on a read; {@code 4392} invalid config (a blank required field
 * on a non-null typed field is tolerated — only an all-null upsert with no useful field is rejected as
 * 4392). The tripwire's advisory {@code 4390} lives on the handler path, not here.
 */
@RestController
@RequestMapping("/frontdesk/switchboard")
@ConditionalOnProperty(prefix = "kmosf.modules.frontdesk", name = "enabled")
public class SwitchboardController {

    private final SwitchboardConfigRepository configs;
    private final SwitchboardDeflectionLogRepository deflectionLogs;
    private final TenantModuleRegistry modules;

    public SwitchboardController(SwitchboardConfigRepository configs,
                                SwitchboardDeflectionLogRepository deflectionLogs,
                                TenantModuleRegistry modules) {
        this.configs = configs;
        this.deflectionLogs = deflectionLogs;
        this.modules = modules;
    }

    /** Read the tenant's Switchboard logistics config (4391 if none has been created yet). */
    @GetMapping("/config")
    public Mono<SwitchboardConfig> getConfig() {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> configs.findByTenantId(ctx.tenantId())
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Switchboard config not found for this tenant", 4391, 404))));
    }

    /**
     * Upsert the tenant's Switchboard logistics config (one row per tenant). Explicit-boolean
     * find-then-update (never {@code switchIfEmpty(create)}): an existing row is updated in place
     * (preserving id/version/timestamps), else a fresh row is created.
     */
    @PutMapping("/config")
    public Mono<SwitchboardConfig> upsertConfig(@RequestBody ConfigRequest body) {
        validate(body);
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> configs.findByTenantId(ctx.tenantId())
                        .map(java.util.Optional::of)
                        .defaultIfEmpty(java.util.Optional.empty())
                        .flatMap(existing -> {
                            SwitchboardConfig toSave = existing.isPresent()
                                    ? body.applyTo(existing.get())
                                    : body.toNewEntity(ctx.tenantId());
                            return configs.save(toSave);
                        }));
    }

    /** The PHI-free deflection analytics (logistics-handled vs tripwire vs handoff, + the rate). */
    @GetMapping("/deflection-stats")
    public Mono<SwitchboardDeflectionStats> deflectionStats() {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> Mono.zip(
                                deflectionLogs.countByTenantIdAndCategory(
                                        ctx.tenantId(), SwitchboardDeflectionCategory.LOGISTICS)
                                        .defaultIfEmpty(0L),
                                deflectionLogs.countByTenantIdAndCategory(
                                        ctx.tenantId(), SwitchboardDeflectionCategory.TRIPWIRE)
                                        .defaultIfEmpty(0L),
                                deflectionLogs.countByTenantIdAndCategory(
                                        ctx.tenantId(), SwitchboardDeflectionCategory.HANDOFF)
                                        .defaultIfEmpty(0L))
                        .map(t -> SwitchboardDeflectionStats.of(t.getT1(), t.getT2(), t.getT3())));
    }

    /** Both modules (frontdesk AND responder) loaded + enabled for the tenant, then ADMIN. */
    private Mono<Void> guard() {
        return modules.requireEnabled(FrontDeskAutoConfiguration.MODULE_KEY)
                .then(modules.requireEnabled(ResponderAutoConfiguration.MODULE_KEY))
                .then(RoleGuard.requireRole("ADMIN"));
    }

    private static void validate(ConfigRequest body) {
        if (body == null) {
            throw new DigiPresBeException("Switchboard config body is required", 4392, 400);
        }
    }

    /**
     * The config upsert request — drops server-managed fields (id/tenantId/version/timestamps).
     *
     * @param hoursText                free-text office hours
     * @param locationText             free-text location / address / directions
     * @param acceptingNewPatients     whether the practice is accepting new patients (defaults true on create)
     * @param acceptingNewPatientsText optional override copy for the accepting-new-patients answer
     * @param bookingInstructions      how a patient books a new appointment
     * @param rescheduleInstructions   how a patient reschedules or cancels
     * @param intakeFormUrl            link to the new-patient intake forms
     * @param reviewLinkUrl            link where a patient can leave a review
     * @param answerOverrides          optional per-intent fully-rendered answer overrides
     * @param safeTripwireReply        the safe reply for a clinical-tripwire message
     */
    public record ConfigRequest(
            String hoursText,
            String locationText,
            Boolean acceptingNewPatients,
            String acceptingNewPatientsText,
            String bookingInstructions,
            String rescheduleInstructions,
            String intakeFormUrl,
            String reviewLinkUrl,
            Map<String, String> answerOverrides,
            String safeTripwireReply) {

        SwitchboardConfig toNewEntity(UUID tenantId) {
            return SwitchboardConfig.builder()
                    .tenantId(tenantId)
                    .hoursText(hoursText)
                    .locationText(locationText)
                    .acceptingNewPatients(acceptingNewPatients == null || acceptingNewPatients)
                    .acceptingNewPatientsText(acceptingNewPatientsText)
                    .bookingInstructions(bookingInstructions)
                    .rescheduleInstructions(rescheduleInstructions)
                    .intakeFormUrl(intakeFormUrl)
                    .reviewLinkUrl(reviewLinkUrl)
                    .answerOverrides(answerOverrides == null ? new HashMap<>() : new HashMap<>(answerOverrides))
                    .safeTripwireReply(safeTripwireReply)
                    .build();
        }

        SwitchboardConfig applyTo(SwitchboardConfig existing) {
            return existing.toBuilder()
                    .hoursText(hoursText)
                    .locationText(locationText)
                    .acceptingNewPatients(acceptingNewPatients == null
                            ? existing.isAcceptingNewPatients() : acceptingNewPatients)
                    .acceptingNewPatientsText(acceptingNewPatientsText)
                    .bookingInstructions(bookingInstructions)
                    .rescheduleInstructions(rescheduleInstructions)
                    .intakeFormUrl(intakeFormUrl)
                    .reviewLinkUrl(reviewLinkUrl)
                    .answerOverrides(answerOverrides == null ? new HashMap<>() : new HashMap<>(answerOverrides))
                    .safeTripwireReply(safeTripwireReply)
                    .build();
        }
    }
}
