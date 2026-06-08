package com.kumouri.kmodigipresbe.module.frontdesk.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.integration.twilio.voice.TwilioVoicemailService;
import com.kumouri.kmodigipresbe.model.activity.ActivityDirection;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.module.frontdesk.FrontDeskAutoConfiguration;
import com.kumouri.kmodigipresbe.module.frontdesk.automation.RecallLog;
import com.kumouri.kmodigipresbe.module.frontdesk.automation.RecallLogRepository;
import com.kumouri.kmodigipresbe.module.frontdesk.controller.dto.CallbackInboxItemDTO;
import com.kumouri.kmodigipresbe.module.frontdesk.controller.dto.RecallDueDTO;
import com.kumouri.kmodigipresbe.module.frontdesk.model.Appointment;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentRepository;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentStatus;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * FrontDesk IQ (FD-5a) — the two staff-facing board reads backing the FD-5 recall board + callback
 * inbox FE. FD-2 built the recall lapsed-contact selector + the {@code RecallLog}, and FD-3 built the
 * PHI-free voicemail callback {@code Activity(CALL, INBOUND)}, but neither exposed a read; this is that
 * read. The other two FD-5 surfaces already have their reads (the risk-sorted day view =
 * {@code NoShowRiskController GET /frontdesk/risk/appointments}; the review inbox =
 * {@code FrontDeskReviewReplyController GET /frontdesk/reviews}).
 *
 * <h2>Endpoints (under {@code spring.webflux.base-path=/api/v1})</h2>
 * <ul>
 *   <li>{@code GET /frontdesk/recall} — the recall board: the lapsed patients due for recall/recare
 *       (most-recent visit older than the recall window AND no upcoming appointment — the SAME selector
 *       FD-2's {@code RecallDetectorJob} sweeps), most-overdue first, each a lean {@link RecallDueDTO}
 *       (contactId, name, lastVisitAt, daysSinceLastVisit, nudgedThisPeriod).</li>
 *   <li>{@code GET /frontdesk/callbacks} — the callback inbox: the after-hours health voicemail
 *       callbacks (FD-3's {@code CALL/INBOUND} activities, fence F2 — the redaction-marker body),
 *       newest first, each a logistics-only {@link CallbackInboxItemDTO} (activityId, contactId,
 *       callerName, callbackPhone, intentBucket, callbackRequested, receivedAt). <strong>NEVER a
 *       transcript</strong> — the DTO has no body/transcript/recording field and the read reads only the
 *       logistics {@code payload.extractedJson} + caller-ID, so the spoken words can never leave the data
 *       layer (fence F2; asserted in {@code FrontDeskBoardReadIT}).</li>
 * </ul>
 *
 * <h2>Gating (the {@code NoShowRiskController} / {@code WaitlistBoardController} /
 * {@code ConciergeConversationController} precedent)</h2>
 * <ul>
 *   <li>{@link ConditionalOnProperty}-gated on {@code kmosf.modules.frontdesk.enabled} — so this
 *       controller is absent from the generated OpenAPI spec when the module is off (the
 *       {@code NoShowRiskController} precedent, where {@code OpenApiEndpointIT} runs without the
 *       frontdesk flag);</li>
 *   <li>per-tenant module membership via {@link TenantModuleRegistry#requireEnabled(String)} — a tenant
 *       without {@code frontdesk} gets the shared {@code 1130}/{@code 1132} module-gate not-enabled
 *       response (the {@code WaitlistBoardController} 1132 posture);</li>
 *   <li>{@link RoleGuard#requireRole "STAFF"} — a recall board / callback inbox is staff-only
 *       ({@code 1800} otherwise), matching {@code NoShowRiskController}'s CRUD + {@code WaitlistBoardController}.</li>
 * </ul>
 *
 * <p>Purely additive: a read over the FD-1..FD-3 collections that does not touch FD-1..FD-4 behavior.
 * The FD-5a error band reserves {@code 4295-4299} for future board-read growth; this read mints NO new
 * code (it reuses the shared module-gate {@code 1130}/{@code 1132} and role-gate {@code 1800}). The
 * recall window is the SAME {@code kmosf.frontdesk.recall.window-days} (default 180) the FD-2 job uses,
 * so the read and the sweep agree on who is lapsed.
 */
@RestController
@RequestMapping("/frontdesk")
@ConditionalOnProperty(prefix = "kmosf.modules.frontdesk", name = "enabled")
@RequiredArgsConstructor
public class FrontDeskBoardController {

    private static final DateTimeFormatter ISO_WEEK = DateTimeFormatter.ofPattern("YYYY-'W'ww");

    private final AppointmentRepository appointments;
    private final RecallLogRepository recallLogs;
    private final ContactRepository contacts;
    private final ActivityRepository activities;
    private final TenantModuleRegistry modules;

    /**
     * The recall window in days — the SAME config the FD-2 {@code RecallDetectorJob} reads
     * ({@code kmosf.frontdesk.recall.window-days}, default 180), so the read's lapsed set matches the
     * nightly sweep's.
     */
    @Value("${kmosf.frontdesk.recall.window-days:180}")
    private long recallWindowDays;

    private final Clock clock = Clock.systemUTC();

    /**
     * The recall board — lapsed patients due for recall/recare, most-overdue first. STAFF + module
     * gated. Mirrors the FD-2 selector: a contact whose most-recent visit ({@code lastVisitAt} or a
     * COMPLETED appointment's start) is older than the recall window AND who has no upcoming
     * (SCHEDULED/CONFIRMED, future) appointment. Enriched with the contact's display name and whether
     * the nightly sweep already nudged them this period (so a staffer avoids a manual double-nudge).
     */
    @GetMapping("/recall")
    public Flux<RecallDueDTO> recallBoard() {
        return guard().thenMany(TenantContextHolder.required().flatMapMany(ctx -> {
            UUID tenantId = ctx.tenantId();
            Instant now = clock.instant();
            Instant lapsedBefore = now.minus(Duration.ofDays(recallWindowDays));
            String periodKey = ISO_WEEK.format(now.atZone(ZoneOffset.UTC));

            Mono<Set<UUID>> nudgedThisPeriod = recallLogs
                    .findByTenantIdAndPeriodKey(tenantId, periodKey)
                    .filter(RecallLog::isNudged)
                    .map(RecallLog::getContactId)
                    .collect(Collectors.toSet());

            return appointments.findAllByTenantId(tenantId)
                    .collectList()
                    .zipWith(nudgedThisPeriod)
                    .flatMapMany(tuple -> {
                        Map<UUID, Instant> lapsed = lapsedLastVisitByContact(tuple.getT1(), now, lapsedBefore);
                        Set<UUID> nudged = tuple.getT2();
                        return Flux.fromIterable(lapsed.entrySet())
                                .concatMap(e -> resolveName(tenantId, e.getKey())
                                        .map(name -> new RecallDueDTO(
                                                e.getKey(),
                                                name.orElse(null),
                                                e.getValue(),
                                                Duration.between(e.getValue(), now).toDays(),
                                                nudged.contains(e.getKey()))))
                                .sort(Comparator.comparingLong(RecallDueDTO::daysSinceLastVisit).reversed());
                    });
        }));
    }

    /**
     * The callback inbox — the after-hours health voicemail callbacks (FD-3), newest first. STAFF +
     * module gated. <strong>Fence F2:</strong> selects only the {@code CALL/INBOUND} activities whose
     * body is the FD-3 transcript-redaction marker (produced ONLY by the health front-desk strategy),
     * and projects them through {@link CallbackInboxItemDTO}, which reads ONLY the logistics
     * {@code payload.extractedJson} + caller-ID and has no transcript/body/recording field — so the read
     * can never surface the spoken words.
     */
    @GetMapping("/callbacks")
    public Flux<CallbackInboxItemDTO> callbackInbox() {
        return guard().thenMany(TenantContextHolder.required().flatMapMany(ctx ->
                activities.findAllByTenantIdAndTypeAndDirectionAndBodyOrderByOccurredAtDesc(
                                ctx.tenantId(),
                                ActivityType.CALL,
                                ActivityDirection.INBOUND,
                                TwilioVoicemailService.TRANSCRIPT_REDACTED_MARKER)
                        .map(CallbackInboxItemDTO::from)));
    }

    /**
     * The set of lapsed contacts → their most-recent-visit timestamp: most-recent visit older than the
     * window, AND no upcoming (SCHEDULED/CONFIRMED, future) appointment. The exact FD-2
     * {@code RecallDetectorJob.lapsedContacts} computation, but keeping the timestamp (the read needs it
     * for {@code lastVisitAt} / {@code daysSinceLastVisit}; the job only needed the ids). Computed from
     * the already-loaded appointment list (one DB read per tenant, like the FD-1 scorer).
     */
    private Map<UUID, Instant> lapsedLastVisitByContact(List<Appointment> all, Instant now,
                                                        Instant lapsedBefore) {
        // Contacts with any upcoming appointment are NOT lapsed — exclude them.
        Set<UUID> hasUpcoming = new HashSet<>();
        for (Appointment a : all) {
            if (a.getContactId() == null) continue;
            if ((a.getStatus() == AppointmentStatus.SCHEDULED
                    || a.getStatus() == AppointmentStatus.CONFIRMED)
                    && a.getScheduledStart() != null && a.getScheduledStart().isAfter(now)) {
                hasUpcoming.add(a.getContactId());
            }
        }

        // Most-recent visit timestamp per contact (lastVisitAt or a COMPLETED appointment's start).
        Map<UUID, Instant> lastVisitByContact = new HashMap<>();
        for (Appointment a : all) {
            UUID cid = a.getContactId();
            if (cid == null) continue;
            Instant candidate = null;
            if (a.getLastVisitAt() != null) {
                candidate = a.getLastVisitAt();
            }
            if (a.getStatus() == AppointmentStatus.COMPLETED && a.getScheduledStart() != null) {
                if (candidate == null || a.getScheduledStart().isAfter(candidate)) {
                    candidate = a.getScheduledStart();
                }
            }
            if (candidate == null) continue;
            Instant existing = lastVisitByContact.get(cid);
            if (existing == null || candidate.isAfter(existing)) {
                lastVisitByContact.put(cid, candidate);
            }
        }

        Map<UUID, Instant> lapsed = new HashMap<>();
        for (Map.Entry<UUID, Instant> e : lastVisitByContact.entrySet()) {
            if (!hasUpcoming.contains(e.getKey()) && e.getValue().isBefore(lapsedBefore)) {
                lapsed.put(e.getKey(), e.getValue());
            }
        }
        return lapsed;
    }

    /**
     * The contact's display name (best-effort) for the recall card. Empty when the contact is no longer
     * materialized — a missing contact never fails the read (the RE-5a {@code resolveLeadTier} posture).
     */
    private Mono<java.util.Optional<String>> resolveName(UUID tenantId, UUID contactId) {
        return contacts.findByTenantIdAndId(tenantId, contactId)
                .map(c -> java.util.Optional.ofNullable(displayName(c)))
                .defaultIfEmpty(java.util.Optional.empty());
    }

    private static String displayName(Contact c) {
        if (c.getDisplayName() != null && !c.getDisplayName().isBlank()) {
            return c.getDisplayName();
        }
        String first = c.getFirstName() == null ? "" : c.getFirstName().trim();
        String last = c.getLastName() == null ? "" : c.getLastName().trim();
        String joined = (first + " " + last).trim();
        return joined.isBlank() ? null : joined;
    }

    /** frontdesk module loaded + enabled for the tenant, then STAFF — the NoShowRiskController order. */
    private Mono<Void> guard() {
        return modules.requireEnabled(FrontDeskAutoConfiguration.MODULE_KEY)
                .then(RoleGuard.requireRole("STAFF"));
    }
}
