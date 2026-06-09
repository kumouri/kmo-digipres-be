package com.kumouri.kmodigipresbe.controller.waitlist;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.model.idempotency.IdempotentRoute;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistEntry;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistOffer;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistSlot;
import com.kumouri.kmodigipresbe.module.waitlist.WaitlistAutoConfiguration;
import com.kumouri.kmodigipresbe.repository.waitlist.WaitlistEntryRepository;
import com.kumouri.kmodigipresbe.repository.waitlist.WaitlistOfferRepository;
import com.kumouri.kmodigipresbe.service.waitlist.GapFillEngine;
import com.kumouri.kmodigipresbe.service.waitlist.WaitlistOfferExpiryService;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * E4 — admin surface for the vertical-agnostic Gap-Fill Waitlist engine: {@link WaitlistEntry} CRUD, an
 * admin gap-fill trigger, an expiry-sweep trigger, and the offer-board read.
 *
 * <h2>Gating (the {@code NurtureCampaignController} precedent)</h2>
 * <ul>
 *   <li>{@code @ConditionalOnProperty(prefix="kmosf.modules.waitlist", name="enabled",
 *       matchIfMissing=true)} — present by default; absent from the OpenAPI spec only when the module is
 *       explicitly disabled (so it 404s).</li>
 *   <li>per-tenant membership via {@link TenantModuleRegistry#requireEnabled(String)} (1130/1132).</li>
 *   <li>{@link RoleGuard#requireRole "ADMIN"} on every endpoint (1800 otherwise).</li>
 * </ul>
 * Base path {@code /api/v1} (via {@code spring.webflux.base-path}), so these map to {@code /api/v1/waitlist...}.
 *
 * <h2>Idempotency</h2>
 * {@code @IdempotentRoute} on the side-effecting POSTs that issue offers / sweep (each returns a non-empty
 * body — the {@code @IdempotentRoute} response-tee requirement, the {@code spawn-now} lesson). Entry create
 * is also idempotency-keyed. List / get / update / cancel are not.
 *
 * <p><strong>Note:</strong> the gap-fill + sweep triggers here are the <em>admin / demo</em> path. In
 * production a consumer (the Health "RescheduleFlow" T7) calls {@code GapFillEngine.gapFill(...)} /
 * {@code WaitlistClaimEngine.claim(...)} directly from its freed-slot + inbound-YES seams; this controller
 * is for waitlist administration + a manual trigger.
 */
@RestController
@RequestMapping("/waitlist")
@ConditionalOnProperty(prefix = "kmosf.modules.waitlist", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class WaitlistEngineController {

    private final WaitlistEntryRepository entries;
    private final WaitlistOfferRepository offers;
    private final GapFillEngine gapFillEngine;
    private final WaitlistOfferExpiryService expiryService;
    private final TenantModuleRegistry modules;

    // ── Waitlist entry CRUD ───────────────────────────────────────────────────

    /** Create a waitlist entry (4351 if contactId blank). */
    @PostMapping("/entries")
    @IdempotentRoute
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<WaitlistEntry> createEntry(@RequestBody EntryRequest body) {
        return guard().then(TenantContextHolder.required()).flatMap(ctx -> {
            if (body == null || body.contactId() == null) {
                return Mono.error(new DigiPresBeException(
                        "Waitlist entry requires a contactId", 4351, 400));
            }
            WaitlistEntry entry = WaitlistEntry.builder()
                    .tenantId(ctx.tenantId())
                    .contactId(body.contactId())
                    .slotType(body.slotType())
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

    /** List the tenant's waitlist entries, newest join first. */
    @GetMapping("/entries")
    public Flux<WaitlistEntry> listEntries() {
        return guard().thenMany(TenantContextHolder.required()
                .flatMapMany(ctx -> entries.findByTenantIdOrderByCreatedAtDesc(ctx.tenantId())));
    }

    /** Get one waitlist entry (4350 if not found). */
    @GetMapping("/entries/{id}")
    public Mono<WaitlistEntry> getEntry(@PathVariable UUID id) {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> entries.findByTenantIdAndId(ctx.tenantId(), id)
                        .switchIfEmpty(Mono.error(WaitlistEngineController::entryNotFound)));
    }

    /** Update a waitlist entry in place (replaces filters / window / consent / stats / notes / status). */
    @PutMapping("/entries/{id}")
    public Mono<WaitlistEntry> updateEntry(@PathVariable UUID id, @RequestBody EntryRequest body) {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> entries.findByTenantIdAndId(ctx.tenantId(), id)
                        .switchIfEmpty(Mono.error(WaitlistEngineController::entryNotFound))
                        .flatMap(existing -> entries.save(applyTo(existing, body))));
    }

    /** Cancel a waitlist entry (soft — sets status CANCELLED so it is never offered). */
    @DeleteMapping("/entries/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> cancelEntry(@PathVariable UUID id) {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> entries.findByTenantIdAndId(ctx.tenantId(), id)
                        .switchIfEmpty(Mono.error(WaitlistEngineController::entryNotFound))
                        .flatMap(e -> entries.save(e.toBuilder()
                                .status(WaitlistEntry.Status.CANCELLED).build()))
                        .then());
    }

    // ── Gap-fill + sweep triggers (admin / demo path) ─────────────────────────

    /** Trigger gap-fill for a freed slot; returns the offers-sent count (4352 if the slot is invalid). */
    @PostMapping("/slots/gap-fill")
    @IdempotentRoute
    public Mono<GapFillResult> gapFill(@RequestBody SlotRequest body) {
        return guard().then(TenantContextHolder.required()).flatMap(ctx -> {
            if (body == null || body.slotKey() == null || body.slotKey().isBlank()
                    || body.slotStart() == null) {
                return Mono.error(new DigiPresBeException(
                        "Gap-fill slot requires slotKey and slotStart", 4352, 400));
            }
            WaitlistSlot slot = WaitlistSlot.builder()
                    .slotType(body.slotType())
                    .slotKey(body.slotKey())
                    .providerId(body.providerId())
                    .slotStart(body.slotStart())
                    .slotEnd(body.slotEnd())
                    .durationMinutes(body.durationMinutes() == null ? 0 : body.durationMinutes())
                    .build();
            return gapFillEngine.gapFill(ctx.tenantId(), slot).map(GapFillResult::new);
        });
    }

    /** Trigger a stale-offer expiry sweep; returns the number flipped to EXPIRED. */
    @PostMapping("/offers/sweep-expired")
    @IdempotentRoute
    public Mono<SweepResult> sweepExpired() {
        return guard().then(expiryService.sweepOnce()).map(SweepResult::new);
    }

    /** The offer board — the tenant's recent offers, newest sent first (capped). */
    @GetMapping("/offers")
    public Flux<WaitlistOffer> listOffers() {
        return guard().thenMany(TenantContextHolder.required()
                .flatMapMany(ctx -> offers.findByTenantIdOrderBySentAtDesc(ctx.tenantId()).take(200)));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** waitlist module loaded + enabled for the tenant, then ADMIN. */
    private Mono<Void> guard() {
        return modules.requireEnabled(WaitlistAutoConfiguration.MODULE_KEY)
                .then(RoleGuard.requireRole("ADMIN"));
    }

    private static DigiPresBeException entryNotFound() {
        return new DigiPresBeException("Waitlist entry not found", 4350, 404);
    }

    private static WaitlistEntry applyTo(WaitlistEntry existing, EntryRequest body) {
        WaitlistEntry.WaitlistEntryBuilder b = existing.toBuilder();
        if (body.slotType() != null) b.slotType(body.slotType());
        if (body.providerId() != null) b.providerId(body.providerId());
        if (body.earliestStart() != null) b.earliestStart(body.earliestStart());
        if (body.latestStart() != null) b.latestStart(body.latestStart());
        if (body.smsOptIn() != null) b.smsOptIn(body.smsOptIn());
        if (body.status() != null) b.status(body.status());
        if (body.notes() != null) b.notes(body.notes());
        if (body.priorNoShowCount() != null) b.priorNoShowCount(body.priorNoShowCount());
        if (body.priorVisitCount() != null) b.priorVisitCount(body.priorVisitCount());
        if (body.lastVisitAt() != null) b.lastVisitAt(body.lastVisitAt());
        return b.build();
    }

    /**
     * The waitlist-entry create/update request body — drops server-managed fields (id/tenantId/version/
     * timestamps). All fields optional on update (only non-null fields are applied).
     */
    public record EntryRequest(
            UUID contactId,
            String slotType,
            UUID providerId,
            Instant earliestStart,
            Instant latestStart,
            Boolean smsOptIn,
            WaitlistEntry.Status status,
            String notes,
            Integer priorNoShowCount,
            Integer priorVisitCount,
            Instant lastVisitAt) {
    }

    /** The freed-slot gap-fill request body. */
    public record SlotRequest(
            String slotType,
            String slotKey,
            UUID providerId,
            Instant slotStart,
            Instant slotEnd,
            Integer durationMinutes) {
    }

    /** The gap-fill result — the number of offers actually sent. */
    public record GapFillResult(int offersSent) {
    }

    /** The expiry-sweep result — the number of offers flipped to EXPIRED. */
    public record SweepResult(long expired) {
    }
}
