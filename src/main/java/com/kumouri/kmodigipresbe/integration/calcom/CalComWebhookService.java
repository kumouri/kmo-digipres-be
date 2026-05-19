package com.kumouri.kmodigipresbe.integration.calcom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.calcom.CalComEventAdapter.CalComEvent;
import com.kumouri.kmodigipresbe.integration.calcom.CalComEventAdapter.EventType;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.integration.CalComWebhookEvent;
import com.kumouri.kmodigipresbe.model.meeting.Meeting;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.MeetingRepository;
import com.kumouri.kmodigipresbe.repository.calcom.CalComWebhookEventRepository;
import com.kumouri.kmodigipresbe.service.ActivityCrudService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Receives Cal.com webhook events scoped to a single tenant and drives the
 * Meeting source-of-truth reconciliation (Phase H — H.2 / H-D1 / H-D2).
 *
 * <h2>Structural mirror of {@code StripeWebhookService} (H-D1, §9 item 1)</h2>
 * Every structural choice mirrors the shipped {@code StripeWebhookService}
 * line-shape-for-line-shape:
 * <ol>
 *   <li><strong>Connection lookup → secret → signature verify → parse.</strong>
 *       Not-connected → stable {@code 3901} / 404.
 *       Signature failure → stable {@code 3900} / 401.</li>
 *   <li><strong>Explicit-boolean idempotency probe</strong> on the Cal.com event id:
 *       {@code calComEvents.findByTenantIdAndCalComEventId(...).map(e -> true)
 *       .defaultIfEmpty(false).flatMap(seen -> seen ? Mono.empty() :
 *       processAndRecord(...))} — <strong>NEVER {@code switchIfEmpty(process)}</strong>
 *       (§9 item 3 — the mandated grep target).</li>
 *   <li><strong>Ledger-insert-FIRST</strong> in {@link #processAndRecord}: the
 *       {@link CalComWebhookEvent} row is saved BEFORE any Meeting upsert or
 *       Activity creation. A concurrent re-delivery's second insert hits the unique
 *       {@code tenant_event_idx} → {@code DuplicateKeyException} →
 *       {@code Mono.empty()} = zero second effect.</li>
 *   <li><strong>Duplicate delivery → 200 no-op</strong> (NOT 409).</li>
 *   <li><strong>Tenant resolved from the URL path</strong> → the per-tenant
 *       {@code IntegrationConnection} → never from the webhook payload (§9 item 5).</li>
 *   <li>All work under a synthetic {@code TenantContext(tenantId, null,
 *       Set.of("INTEGRATION_CALCOM"))} via
 *       {@code body.contextWrite(TenantContextHolder.write(ctx))}.</li>
 * </ol>
 *
 * <h2>Meeting reconciliation (H-D2 source-of-truth ADR)</h2>
 * Cal.com is authoritative; {@link Meeting} rows are <em>cached projections</em>.
 * {@code MeetingCrudService} / {@code BookingService} / {@code BookingLinkService}
 * are <strong>NOT modified</strong> — the reconcile is a new additive caller path,
 * not a change to staff booking CRUD (empty diff on those files vs main @24f7e5e).
 *
 * <h2>Error codes (H-D8)</h2>
 * {@code 3900} — Cal.com webhook signature invalid (401);
 * {@code 3901} — Cal.com not connected for tenant (404; {@code 2510} is the
 *     documented cross-integration fallback, but H-D1 prefers a Phase-H-local
 *     code for the Cal.com surface — deviation recorded);
 * {@code 3902} — body not JSON / missing event id (400);
 * {@code 3903} — booking references no resolvable contact (advisory-skip, logged).
 *
 * <p>No live Cal.com anywhere — signatures verified with a test signing secret in
 * tests (§7 hard boundary).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalComWebhookService {

    public static final String PROVIDER = "calcom";

    private final ObjectMapper objectMapper;
    private final IntegrationConnectionRepository connections;
    private final CalComWebhookEventRepository calComEvents;
    private final MeetingRepository meetingRepository;
    private final ContactRepository contactRepository;
    private final ActivityCrudService activityCrudService;
    private final DomainEventPublisher events;

    /**
     * Entry point called by {@code CalComWebhookController}.
     *
     * <p>Tenant is resolved from the URL path (the controller parses the UUID and
     * passes it here). The webhook payload's claimed tenant, if any, is NEVER
     * trusted — only the path-derived {@code IntegrationConnection} is authoritative.
     */
    public Mono<Void> handle(UUID tenantId, String signatureHeader, String rawBody) {
        return connections.findByTenantIdAndProvider(tenantId, PROVIDER)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Cal.com is not connected for this tenant", 3901, 404)))
                .flatMap(conn -> {
                    String secret = conn.getSecrets() == null
                            ? null
                            : conn.getSecrets().get("webhookSigningSecret");
                    if (secret == null || secret.isBlank()) {
                        return Mono.error(new DigiPresBeException(
                                "Tenant's Cal.com webhookSigningSecret is not configured",
                                3901, 404));
                    }
                    // HMAC verify — the ONLY place the signature is checked.
                    // CalComSignatureVerifier isolates the entire signing scheme (H-D1 /
                    // F-D7 adapter-boundary discipline). Failure → 3900 / 401 (H-D8).
                    if (!CalComSignatureVerifier.verify(signatureHeader, rawBody, secret)) {
                        return Mono.error(new DigiPresBeException(
                                "Cal.com webhook signature invalid", 3900, 401));
                    }
                    return process(tenantId, rawBody);
                });
    }

    private Mono<Void> process(UUID tenantId, String rawBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception ex) {
            return Mono.error(new DigiPresBeException(
                    "Cal.com webhook body is not JSON: " + ex.getMessage(), 3902, 400));
        }

        // Delegate ALL payload parsing to CalComEventAdapter — the only class that
        // knows the assumed payload shape (F-D7 adapter-boundary discipline for Cal.com).
        CalComEvent calEvent = CalComEventAdapter.parse(root);

        String eventId = calEvent.eventId();
        if (eventId == null || eventId.isBlank()) {
            // Defensive: without an event id we cannot dedupe — reject (400).
            return Mono.error(new DigiPresBeException(
                    "Cal.com webhook event is missing its id", 3902, 400));
        }

        // Explicit-boolean idempotency probe on the EVENT id (H-D1 / §9 item 3).
        // NOT switchIfEmpty(processAndRecord) — that fires whenever the probe
        // completes empty and would re-process on a cache HIT.
        return calComEvents.findByTenantIdAndCalComEventId(tenantId, eventId)
                .map(e -> true)
                .defaultIfEmpty(false)
                .flatMap(seen -> {
                    if (Boolean.TRUE.equals(seen)) {
                        // Duplicate delivery — acknowledge 200 no-op (NOT 409).
                        log.debug("Cal.com event {} already processed for tenant {} "
                                + "— 200 no-op", eventId, tenantId);
                        return Mono.empty();
                    }
                    return processAndRecord(tenantId, calEvent);
                });
    }

    /**
     * Ledger-insert FIRST (H-D1, §9 item 1), then dispatch the reconcile.
     *
     * <p>The {@link CalComWebhookEvent} row is inserted <strong>before any side
     * effect</strong> so a concurrent re-delivery's second insert hits the unique
     * {@code tenant_event_idx} → {@code DuplicateKeyException} → {@code Mono.empty()}
     * = zero second effect.
     *
     * <p>All work runs under a synthetic {@code TenantContext} so tenant-scoped
     * repository calls inside the reconcile resolve correctly via
     * {@code TenantContextHolder.required()}.
     */
    private Mono<Void> processAndRecord(UUID tenantId, CalComEvent calEvent) {
        String eventId    = calEvent.eventId();
        String eventType  = calEvent.type().name();
        String bookingUid = calEvent.bookingUid();

        CalComWebhookEvent ledger = CalComWebhookEvent.builder()
                .tenantId(tenantId)
                .calComEventId(eventId)
                .eventType(eventType)
                .calComBookingUid(bookingUid)
                .receivedAt(Instant.now())
                .build();

        // LEDGER-INSERT FIRST — the unique index is the hard gate against concurrent
        // re-delivery. onErrorResume(DuplicateKeyException) swallows the race winner's
        // duplicate → 200 no-op, zero second effect.
        Mono<Void> body = calComEvents.save(ledger)
                .onErrorResume(DuplicateKeyException.class, e -> {
                    log.debug("Cal.com event {} concurrently processed for tenant {} "
                            + "— 200 no-op", eventId, tenantId);
                    return Mono.empty();
                })
                .flatMap(savedLedger -> {
                    if (savedLedger == null) {
                        // Concurrent duplicate swallowed above — already Mono.empty()
                        return Mono.empty();
                    }
                    return switch (calEvent.type()) {
                        case BOOKING_CREATED, BOOKING_RESCHEDULED ->
                                reconcileUpsert(tenantId, calEvent, savedLedger);
                        case BOOKING_CANCELLED ->
                                reconcileCancel(tenantId, calEvent, savedLedger);
                        case OTHER -> {
                            log.debug("Cal.com event type {} for tenant {} — ledgered, 200 no-op",
                                    calEvent.type(), tenantId);
                            yield Mono.empty();
                        }
                    };
                });

        TenantContext webhookCtx = new TenantContext(
                tenantId, null, Set.of("INTEGRATION_CALCOM"));
        return body.contextWrite(TenantContextHolder.write(webhookCtx));
    }

    /**
     * Upserts a {@link Meeting} projection keyed by {@code calComBookingUid} (H-D2).
     *
     * <p>If a Meeting with the same {@code calComBookingUid} already exists for the
     * tenant → update it (reschedule). Otherwise create a new one. This is
     * accomplished via an <strong>explicit boolean branch</strong> on the
     * {@code findByTenantIdAndCalComBookingUid} probe —
     * NOT {@code switchIfEmpty(create)}.
     *
     * <p>After saving: emit advisory {@code CALCOM_BOOKING_SYNCED} + best-effort
     * {@code Activity(MEETING)} via the unchanged {@code ActivityCrudService}. If no
     * contact is resolvable → advisory-skip + log code 3903, NOT a hard error.
     */
    private Mono<Void> reconcileUpsert(UUID tenantId, CalComEvent calEvent,
                                       CalComWebhookEvent savedLedger) {
        String bookingUid = calEvent.bookingUid();
        if (bookingUid == null || bookingUid.isBlank()) {
            log.warn("Cal.com booking event {} for tenant {} has no bookingUid — ledgered, skipping reconcile",
                    calEvent.eventId(), tenantId);
            return Mono.empty();
        }

        // Explicit-boolean probe: find existing Meeting by calComBookingUid.
        // NOT switchIfEmpty(create) — explicit boolean branch required (§9 / H-D1).
        return meetingRepository.findByTenantIdAndCalComBookingUid(tenantId, bookingUid)
                .map(m -> true)
                .defaultIfEmpty(false)
                .flatMap(exists -> {
                    if (Boolean.TRUE.equals(exists)) {
                        // Update existing projection
                        return meetingRepository.findByTenantIdAndCalComBookingUid(tenantId, bookingUid)
                                .flatMap(existing -> {
                                    applyBookingFields(existing, calEvent);
                                    return meetingRepository.save(existing);
                                });
                    } else {
                        // Create new projection
                        Meeting newMeeting = Meeting.builder()
                                .tenantId(tenantId)
                                .calComBookingUid(bookingUid)
                                .name(calEvent.title())
                                .start(calEvent.start())
                                .end(calEvent.end())
                                .build();
                        return meetingRepository.save(newMeeting);
                    }
                })
                .flatMap(savedMeeting -> {
                    // Back-fill ledger with meeting correlation
                    savedLedger.setMeetingId(savedMeeting.getId());
                    return calComEvents.save(savedLedger)
                            .then(resolveContactAndEmit(tenantId, calEvent, savedMeeting, savedLedger, false));
                });
    }

    /**
     * Marks an existing Meeting projection cancelled (H-D2 source-of-truth ADR).
     *
     * <p>Explicit-boolean check: if no Meeting exists for the bookingUid → advisory
     * log (no error; the cancellation ledger row is still written for idempotency).
     * After cancel: emit advisory {@code CALCOM_BOOKING_CANCELLED}.
     */
    private Mono<Void> reconcileCancel(UUID tenantId, CalComEvent calEvent,
                                       CalComWebhookEvent savedLedger) {
        String bookingUid = calEvent.bookingUid();
        if (bookingUid == null || bookingUid.isBlank()) {
            log.warn("Cal.com cancel event {} for tenant {} has no bookingUid — ledgered, skipping cancel",
                    calEvent.eventId(), tenantId);
            return Mono.empty();
        }

        return meetingRepository.findByTenantIdAndCalComBookingUid(tenantId, bookingUid)
                .map(m -> true)
                .defaultIfEmpty(false)
                .flatMap(exists -> {
                    if (Boolean.FALSE.equals(exists)) {
                        log.debug("Cal.com cancel event {} for tenant {}: no Meeting found for "
                                + "bookingUid {} — ledgered, advisory skip",
                                calEvent.eventId(), tenantId, bookingUid);
                        emitBookingCancelled(tenantId, bookingUid, null);
                        return Mono.empty();
                    }
                    return meetingRepository.findByTenantIdAndCalComBookingUid(tenantId, bookingUid)
                            .flatMap(existing -> {
                                // Mark cancelled — set name to prefix if not already marked
                                if (existing.getName() != null
                                        && !existing.getName().startsWith("[CANCELLED] ")) {
                                    existing.setName("[CANCELLED] " + existing.getName());
                                }
                                return meetingRepository.save(existing);
                            })
                            .flatMap(saved -> {
                                savedLedger.setMeetingId(saved.getId());
                                return calComEvents.save(savedLedger).then(Mono.defer(() -> {
                                    emitBookingCancelled(tenantId, bookingUid, saved.getId());
                                    return Mono.empty();
                                }));
                            });
                });
    }

    /**
     * Resolves the attendee contact by email, then emits the advisory
     * {@code CALCOM_BOOKING_SYNCED} event + best-effort
     * {@code Activity(MEETING, subjectType=CONTACT)} via the unchanged
     * {@code ActivityCrudService}.
     *
     * <p>If the attendee email references no resolvable contact: advisory-skip +
     * log code 3903, NOT a hard error (G-D4 / H-D1 best-effort precedent).
     */
    private Mono<Void> resolveContactAndEmit(UUID tenantId, CalComEvent calEvent,
                                             Meeting savedMeeting,
                                             CalComWebhookEvent savedLedger,
                                             boolean isCancelled) {
        String attendeeEmail = calEvent.attendeeEmail();
        if (attendeeEmail == null || attendeeEmail.isBlank()) {
            log.debug("Cal.com event {} for tenant {}: no attendee email — advisory skip (3903)",
                    calEvent.eventId(), tenantId);
            emitBookingSynced(tenantId, calEvent.bookingUid(), savedMeeting.getId(), null);
            return Mono.empty();
        }

        return contactRepository.findByTenantAndEmailAddress(tenantId, attendeeEmail)
                .next()  // first match — consistent with the single-email resolution pattern
                .map(c -> true)
                .defaultIfEmpty(false)
                .flatMap(found -> {
                    if (Boolean.FALSE.equals(found)) {
                        log.debug("Cal.com event {} for tenant {}: no Contact found for "
                                + "email {} — advisory skip (3903)",
                                calEvent.eventId(), tenantId, attendeeEmail);
                        emitBookingSynced(tenantId, calEvent.bookingUid(), savedMeeting.getId(), null);
                        return Mono.empty();
                    }
                    return contactRepository.findByTenantAndEmailAddress(tenantId, attendeeEmail)
                            .next()
                            .flatMap(contact -> {
                                // Back-fill ledger with resolved contact
                                savedLedger.setResolvedContactId(contact.getId());

                                emitBookingSynced(tenantId, calEvent.bookingUid(),
                                        savedMeeting.getId(), contact.getId());

                                // Best-effort Activity(MEETING) — a telemetry failure
                                // must NOT fail the ingest (G-D4 precedent).
                                Activity activity = Activity.builder()
                                        .tenantId(tenantId)
                                        .type(ActivityType.MEETING)
                                        .subjectType(SubjectType.CONTACT)
                                        .subjectId(contact.getId())
                                        .summary("Cal.com booking: " + (calEvent.title() != null
                                                ? calEvent.title() : calEvent.bookingUid()))
                                        .payload(Map.of(
                                                "meetingId", savedMeeting.getId().toString(),
                                                "calComBookingUid", calEvent.bookingUid() != null
                                                        ? calEvent.bookingUid() : ""))
                                        .build();

                                return calComEvents.save(savedLedger)
                                        .then(activityCrudService.create(activity)
                                                .onErrorResume(e -> {
                                                    log.warn("Cal.com event {} for tenant {}: "
                                                            + "Activity creation failed (best-effort, "
                                                            + "ignored): {}", calEvent.eventId(),
                                                            tenantId, e.getMessage());
                                                    return Mono.empty();
                                                }))
                                        .then();
                            });
                });
    }

    private void emitBookingSynced(UUID tenantId, String bookingUid, UUID meetingId, UUID contactId) {
        Map<String, Object> payload = new HashMap<>();
        if (bookingUid != null) payload.put("calComBookingUid", bookingUid);
        if (meetingId != null) payload.put("meetingId", meetingId.toString());
        if (contactId != null) payload.put("contactId", contactId.toString());
        events.publish(DomainEvent.of(
                DomainEventType.CALCOM_BOOKING_SYNCED, tenantId,
                meetingId != null ? meetingId : UUID.randomUUID(),
                payload));
    }

    private void emitBookingCancelled(UUID tenantId, String bookingUid, UUID meetingId) {
        Map<String, Object> payload = new HashMap<>();
        if (bookingUid != null) payload.put("calComBookingUid", bookingUid);
        if (meetingId != null) payload.put("meetingId", meetingId.toString());
        events.publish(DomainEvent.of(
                DomainEventType.CALCOM_BOOKING_CANCELLED, tenantId,
                meetingId != null ? meetingId : UUID.randomUUID(),
                payload));
    }

    private void applyBookingFields(Meeting existing, CalComEvent calEvent) {
        if (calEvent.title() != null) existing.setName(calEvent.title());
        if (calEvent.start() != null) existing.setStart(calEvent.start());
        if (calEvent.end() != null) existing.setEnd(calEvent.end());
    }
}
