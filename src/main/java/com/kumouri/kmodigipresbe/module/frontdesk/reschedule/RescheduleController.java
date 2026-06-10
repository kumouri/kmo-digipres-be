package com.kumouri.kmodigipresbe.module.frontdesk.reschedule;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.model.idempotency.IdempotentRoute;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistEntry;
import com.kumouri.kmodigipresbe.module.frontdesk.FrontDeskAutoConfiguration;
import com.kumouri.kmodigipresbe.module.waitlist.WaitlistAutoConfiguration;
import com.kumouri.kmodigipresbe.repository.waitlist.WaitlistEngineEntryRepository;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;
// NOTE: this controller depends ONLY on always-present beans (the WaitlistEngineEntryRepository +
// RescheduleFillLogRepository Spring Data repos + TenantModuleRegistry) — never the both-module-gated
// RescheduleAnalyticsService. That is deliberate: the controller is component-scanned + gated on the
// frontdesk property alone, so when frontdesk is ON but waitlist is OFF (no RescheduleFlowAutoConfiguration
// beans) the controller still loads and the per-tenant requireEnabled(waitlist) returns 1132 at request
// time (the SwitchboardController posture — depend only on always-present repos, gate per-request).

/**
 * T7 (Health "RescheduleFlow") — the admin surface for the health waitlist + the fill-funnel analytics. The
 * patient-facing pieces (the offer SMS, the inbound-YES claim) ride the E4 engine + the E2 responder seam,
 * not this controller — this is the staff console (join the waitlist, see the waitlist, read the fill rate).
 * The {@code SwitchboardController} / {@code FrontDeskNurtureController} precedent.
 *
 * <h2>Endpoints (under {@code spring.webflux.base-path=/api/v1})</h2>
 * <ul>
 *   <li>{@code POST /frontdesk/reschedule/waitlist} (body {@link WaitlistJoinRequest}) → join the health
 *       waitlist; creates a generic {@link WaitlistEntry} with {@code slotType="health-appt"}. 4421 if the
 *       body has no {@code contactId}. {@code @IdempotentRoute}.</li>
 *   <li>{@code GET  /frontdesk/reschedule/waitlist} → the tenant's health waitlist entries (newest first).</li>
 *   <li>{@code GET  /frontdesk/reschedule/fill-stats} → {@link RescheduleFillStats}
 *       (cancellations / offers / claims / filled / fillRate).</li>
 * </ul>
 *
 * <h2>PHI-free (fence F1)</h2>
 * The waitlist join accepts <strong>logistics-only</strong> preferences (appt provider + earliest/latest
 * window + the entry-carried show stats) — there is no clinical field to set. The generic
 * {@link WaitlistEntry} carries no clinical content, and {@code slotType} is the fixed logistics token
 * {@code "health-appt"} (never a procedure).
 *
 * <h2>Gating (BOTH modules — the {@code SwitchboardController} precedent)</h2>
 * <ul>
 *   <li>{@code @ConditionalOnProperty(kmosf.modules.frontdesk.enabled)} so the controller is absent from the
 *       generated OpenAPI spec when frontdesk is off ({@code OpenApiEndpointIT} runs without the flag);</li>
 *   <li>per-tenant membership via {@link TenantModuleRegistry#requireEnabled(String)} for <strong>both</strong>
 *       {@code frontdesk} AND {@code waitlist} (1130/1132 otherwise);</li>
 *   <li>{@link RoleGuard#requireRole "ADMIN"} on every endpoint (1800 otherwise).</li>
 * </ul>
 *
 * <h2>Errors (4420-4429 band)</h2>
 * {@code 4421} waitlist-join body has no contactId (400). The fill-funnel read mints no code.
 */
@RestController
@RequestMapping("/frontdesk/reschedule")
@ConditionalOnProperty(prefix = "kmosf.modules.frontdesk", name = "enabled")
@RequiredArgsConstructor
public class RescheduleController {

    private final WaitlistEngineEntryRepository entries;
    private final RescheduleFillLogRepository fillLogs;
    private final TenantModuleRegistry modules;

    /**
     * Join the health waitlist (logistics-only prefs). Creates a generic {@link WaitlistEntry} with
     * {@code slotType="health-appt"} so a freed health slot's gap-fill ranks + offers it. 4421 if no
     * {@code contactId}.
     */
    @PostMapping("/waitlist")
    @IdempotentRoute
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<WaitlistEntry> joinWaitlist(@RequestBody WaitlistJoinRequest body) {
        return guard().then(TenantContextHolder.required()).flatMap(ctx -> {
            if (body == null || body.contactId() == null) {
                return Mono.error(new DigiPresBeException(
                        "Reschedule waitlist join requires a contactId", 4421, 400));
            }
            WaitlistEntry entry = WaitlistEntry.builder()
                    .tenantId(ctx.tenantId())
                    .contactId(body.contactId())
                    .slotType(FrontDeskSlotMaterializer.SLOT_TYPE)
                    .providerId(body.providerId())
                    .earliestStart(body.earliestStart())
                    .latestStart(body.latestStart())
                    .smsOptIn(body.smsOptIn() == null || body.smsOptIn())
                    .status(WaitlistEntry.Status.OPEN)
                    .notes(body.notes())
                    .priorNoShowCount(body.priorNoShowCount() == null ? 0 : body.priorNoShowCount())
                    .priorVisitCount(body.priorVisitCount() == null ? 0 : body.priorVisitCount())
                    .lastVisitAt(body.lastVisitAt())
                    .build();
            return entries.save(entry);
        });
    }

    /** The tenant's health waitlist entries, newest join first. */
    @GetMapping("/waitlist")
    public Flux<WaitlistEntry> listWaitlist() {
        return guard().thenMany(TenantContextHolder.required()
                .flatMapMany(ctx -> entries.findByTenantIdOrderByCreatedAtDesc(ctx.tenantId())
                        .filter(e -> FrontDeskSlotMaterializer.SLOT_TYPE.equals(e.getSlotType()))));
    }

    /**
     * The PHI-free fill-funnel analytics (cancellations → offers → claims → filled + the fill rate).
     * Computed directly off the always-present {@link RescheduleFillLogRepository} (the
     * {@code SwitchboardController.deflectionStats} pattern) so the controller carries no both-module-gated
     * dependency.
     */
    @GetMapping("/fill-stats")
    public Mono<RescheduleFillStats> fillStats() {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> Mono.zip(
                                fillLogs.countByTenantIdAndEvent(ctx.tenantId(),
                                        RescheduleFillEvent.CANCELLATION).defaultIfEmpty(0L),
                                fillLogs.countByTenantIdAndEvent(ctx.tenantId(),
                                        RescheduleFillEvent.OFFER).defaultIfEmpty(0L),
                                fillLogs.countByTenantIdAndEvent(ctx.tenantId(),
                                        RescheduleFillEvent.CLAIM).defaultIfEmpty(0L),
                                fillLogs.countByTenantIdAndEvent(ctx.tenantId(),
                                        RescheduleFillEvent.FILLED).defaultIfEmpty(0L))
                        .map(t -> RescheduleFillStats.of(t.getT1(), t.getT2(), t.getT3(), t.getT4())));
    }

    /** Both modules (frontdesk AND waitlist) loaded + enabled for the tenant, then ADMIN. */
    private Mono<Void> guard() {
        return modules.requireEnabled(FrontDeskAutoConfiguration.MODULE_KEY)
                .then(modules.requireEnabled(WaitlistAutoConfiguration.MODULE_KEY))
                .then(RoleGuard.requireRole("ADMIN"));
    }

    /**
     * The health waitlist-join request — drops server-managed fields (id/tenantId/version/timestamps) + the
     * fixed {@code slotType}. Logistics only (no clinical field). All fields except {@code contactId} optional.
     *
     * @param contactId        the patient/contact joining the waitlist (required)
     * @param providerId       optional — only match a freed slot with this provider; null = any
     * @param earliestStart    optional lower bound on an acceptable slot start; null = no lower bound
     * @param latestStart      optional upper bound on an acceptable slot start; null = no upper bound
     * @param smsOptIn         SMS consent — defaults true (the waitlist join IS the opt-in; TCPA-safe)
     * @param notes            optional free-form scheduling note (e.g. "any afternoon works") — NOT clinical
     * @param priorNoShowCount optional show-likelihood signal — prior no-shows (0 = none/unknown)
     * @param priorVisitCount  optional show-likelihood signal — prior completed visits (0 = cold start)
     * @param lastVisitAt      optional show-likelihood signal — last visit timestamp (null = unknown)
     */
    public record WaitlistJoinRequest(
            UUID contactId,
            UUID providerId,
            Instant earliestStart,
            Instant latestStart,
            Boolean smsOptIn,
            String notes,
            Integer priorNoShowCount,
            Integer priorVisitCount,
            Instant lastVisitAt) {
    }
}
