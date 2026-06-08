package com.kumouri.kmodigipresbe.module.frontdesk.automation;

import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.sequence.Sequence;
import com.kumouri.kmodigipresbe.module.frontdesk.FrontDeskAutoConfiguration;
import com.kumouri.kmodigipresbe.module.frontdesk.model.Appointment;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentRepository;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentStatus;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.SequenceRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.service.sequence.SequenceCrudService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * FrontDesk IQ (FD-2) — recall / recare re-engagement sweep. A nightly per-tenant {@code @Scheduled} job
 * that finds <strong>lapsed</strong> patients — a contact whose most-recent visit ({@code lastVisitAt} or
 * the most-recent COMPLETED {@link Appointment}) is older than a configurable window (default 180d)
 * <em>and</em> who has no upcoming appointment — and re-engages them: it enrolls them into the practice's
 * recall {@link Sequence} (the shipped {@code SequenceEngine} cadence — the same machinery a vet's
 * annual-wellness recall uses) and/or sends a <strong>generic</strong> recare nudge SMS. The
 * {@code CoverageNudgeJob} sweep posture, applied to the {@code frontdesk} recall signal.
 *
 * <h2>The PHI fence F3</h2>
 * The recare nudge copy is GENERIC ("time for your visit / let's get you back on the schedule") and
 * NEVER names a procedure, provider, department, or visit-type. The recall is keyed off a metadata
 * timestamp ({@code lastVisitAt} / a COMPLETED appointment's {@code scheduledStart}), <em>not</em> a
 * clinical reason for return (fence F1/F3). A release-blocking IT asserts the forbidden-token set is
 * absent from the nudge body.
 *
 * <h2>Idempotent per (tenant, contact, period) — ledger-insert-FIRST</h2>
 * For each lapsed contact the job computes the current {@code periodKey} (ISO week), runs an
 * explicit-boolean {@link RecallLog} probe, and only if not-yet-seen does it insert the row FIRST (unique
 * {@code tenant_contact_period_idx}, with {@code onErrorResume(DuplicateKeyException → empty)} as the
 * concurrent-re-run backstop) and then act — so a restart / a second sweep in the same week produces ZERO
 * duplicate nudge and ZERO duplicate enrollment. Never {@code switchIfEmpty(send)}.
 *
 * <h2>TCPA / consent + best-effort</h2>
 * The nudge SMS is consent-gated exactly like {@link FrontDeskConfirmationService}: skipped for a contact
 * with the {@value FrontDeskConfirmationService#SMS_OPT_OUT_TAG} tag or no phone. The enrollment and the
 * SMS are each best-effort ({@code onErrorResume}); one tenant's / one contact's failure never aborts the
 * sweep. <strong>Blast radius zero:</strong> wired as a {@code @Bean} in {@link FrontDeskAutoConfiguration}
 * (so it does not exist for non-frontdesk servers) and the sweep additionally filters to ACTIVE tenants
 * whose {@code enabledModules} contains {@code "frontdesk"}.
 */
@Slf4j
public class RecallDetectorJob {

    private static final DateTimeFormatter ISO_WEEK = DateTimeFormatter.ofPattern("YYYY-'W'ww");

    private final TenantRepository tenants;
    private final AppointmentRepository appointments;
    private final ContactRepository contacts;
    private final RecallLogRepository recallLogs;
    private final SequenceRepository sequences;
    private final SequenceCrudService sequenceCrud;
    private final TwilioSmsService twilioSms;

    private final long recallWindowDays;
    private final String recallSequenceName;
    private final String nudgeMessage;
    private final Clock clock;

    public RecallDetectorJob(TenantRepository tenants,
                             AppointmentRepository appointments,
                             ContactRepository contacts,
                             RecallLogRepository recallLogs,
                             SequenceRepository sequences,
                             SequenceCrudService sequenceCrud,
                             TwilioSmsService twilioSms,
                             long recallWindowDays,
                             String recallSequenceName,
                             String nudgeMessage) {
        this.tenants = tenants;
        this.appointments = appointments;
        this.contacts = contacts;
        this.recallLogs = recallLogs;
        this.sequences = sequences;
        this.sequenceCrud = sequenceCrud;
        this.twilioSms = twilioSms;
        this.recallWindowDays = recallWindowDays;
        this.recallSequenceName = recallSequenceName == null ? "" : recallSequenceName;
        this.nudgeMessage = nudgeMessage;
        this.clock = Clock.systemUTC();
    }

    /**
     * Scheduled tick — config-driven cron (default nightly 03:30, after the FD-1 scorer's 02:45). Fire-and-
     * forget subscribe on the scheduler thread (never the Netty loop); the per-tenant / per-contact pipeline
     * catches + logs so one failure never aborts the rest.
     */
    @Scheduled(cron = "${kmosf.frontdesk.recall.nightly-cron:0 30 3 * * *}")
    public void scheduledTick() {
        sweepDueOnce().subscribe(
                ignored -> {},
                err -> log.error("RecallDetectorJob tick failed", err));
    }

    /**
     * Visible-for-test entry — runs one full recall sweep across all frontdesk tenants and returns when
     * done, so an IT can drive it deterministically (the {@code CoverageNudgeJob.nudgeDueOnce()} pattern).
     */
    public Mono<Void> sweepDueOnce() {
        String periodKey = currentPeriodKey();
        return tenants.findAll()
                .filter(t -> t.getStatus() == Tenant.TenantStatus.ACTIVE
                        && t.getEnabledModules() != null
                        && t.getEnabledModules().contains(FrontDeskAutoConfiguration.MODULE_KEY))
                .concatMap(t -> sweepTenant(t.getId(), periodKey)
                        .onErrorResume(err -> {
                            log.warn("Recall sweep failed for tenant {}: {}", t.getId(), err.toString());
                            return Mono.empty();
                        }))
                .then();
    }

    private Mono<Void> sweepTenant(UUID tenantId, String periodKey) {
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("AUTOMATION_RECALL"));
        Instant now = clock.instant();
        Instant lapsedBefore = now.minus(Duration.ofDays(recallWindowDays));
        return appointments.findAllByTenantId(tenantId)
                .collectList()
                .flatMap(all -> resolveRecallSequenceId(tenantId)
                        .defaultIfEmpty(NO_SEQUENCE)
                        .flatMap(seqId -> Flux.fromIterable(lapsedContacts(all, now, lapsedBefore))
                                .concatMap(contactId -> recallContact(tenantId, contactId, periodKey,
                                                seqId == NO_SEQUENCE ? null : seqId)
                                        .onErrorResume(err -> {
                                            log.warn("Recall failed for contact {} (tenant {}): {}",
                                                    contactId, tenantId, err.toString());
                                            return Mono.empty();
                                        }))
                                .then()))
                .contextWrite(TenantContextHolder.write(ctx));
    }

    /** Sentinel for "this tenant has no recall Sequence" (so the chain stays non-empty). */
    private static final UUID NO_SEQUENCE = new UUID(0L, 0L);

    /**
     * The set of lapsed contacts for the tenant: most-recent visit older than the window, AND no upcoming
     * (SCHEDULED/CONFIRMED, future) appointment. Computed from the already-loaded appointment list (one DB
     * read per tenant, like the FD-1 scorer's {@code findAllByTenantId}).
     */
    private List<UUID> lapsedContacts(List<Appointment> all, Instant now, Instant lapsedBefore) {
        // Contacts with any upcoming appointment are NOT lapsed — exclude them.
        Set<UUID> hasUpcoming = all.stream()
                .filter(a -> a.getContactId() != null)
                .filter(a -> (a.getStatus() == AppointmentStatus.SCHEDULED
                        || a.getStatus() == AppointmentStatus.CONFIRMED))
                .filter(a -> a.getScheduledStart() != null && a.getScheduledStart().isAfter(now))
                .map(Appointment::getContactId)
                .collect(Collectors.toSet());

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

        return lastVisitByContact.entrySet().stream()
                .filter(e -> !hasUpcoming.contains(e.getKey()))
                .filter(e -> e.getValue().isBefore(lapsedBefore))
                .map(Map.Entry::getKey)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Re-engage one lapsed contact for {@code periodKey}, idempotent: explicit-boolean probe →
     * ledger-insert-FIRST → enroll (if a recall Sequence exists) + send a generic recare nudge. Never
     * {@code switchIfEmpty(send)}.
     */
    private Mono<Void> recallContact(UUID tenantId, UUID contactId, String periodKey, UUID recallSequenceId) {
        return recallLogs.findByTenantIdAndContactIdAndPeriodKey(tenantId, contactId, periodKey)
                .map(e -> true)
                .defaultIfEmpty(false)
                .flatMap(already -> {
                    if (already) {
                        log.debug("FD-2 recall already done for contact {} period {} — skipping",
                                contactId, periodKey);
                        return Mono.empty();
                    }
                    return insertLedgerThenAct(tenantId, contactId, periodKey, recallSequenceId);
                });
    }

    /**
     * Ledger-insert-FIRST (the unique-index exactly-once backstop), then enroll + nudge. A concurrent
     * re-run loses the insert on a {@code DuplicateKeyException} → {@code Mono.empty()} = zero duplicate.
     */
    private Mono<Void> insertLedgerThenAct(UUID tenantId, UUID contactId, String periodKey,
                                           UUID recallSequenceId) {
        RecallLog row = RecallLog.builder()
                .tenantId(tenantId)
                .contactId(contactId)
                .periodKey(periodKey)
                .enrolled(false)
                .nudged(false)
                .sentAt(clock.instant())
                .build();
        return recallLogs.save(row)
                .onErrorResume(DuplicateKeyException.class, e -> {
                    log.debug("FD-2 recall concurrent-fire lost ledger insert for contact {} period {} "
                            + "— zero duplicate", contactId, periodKey);
                    return Mono.empty();
                })
                .flatMap(saved -> act(tenantId, contactId, periodKey, recallSequenceId, saved));
    }

    private Mono<Void> act(UUID tenantId, UUID contactId, String periodKey, UUID recallSequenceId,
                           RecallLog ledger) {
        Mono<Boolean> enrollStep = (recallSequenceId == null)
                ? Mono.just(false)
                : sequenceCrud.enroll(recallSequenceId, contactId)
                        .map(en -> true)
                        .onErrorResume(err -> {
                            log.warn("FD-2 recall enrollment failed for contact {} into sequence {}: {}",
                                    contactId, recallSequenceId, err.toString());
                            return Mono.just(false);
                        });

        return enrollStep.flatMap(enrolled -> nudge(tenantId, contactId)
                .flatMap(nudged -> recallLogs.save(ledger.toBuilder()
                                .enrolled(enrolled)
                                .nudged(nudged)
                                .build())
                        .then()));
    }

    /**
     * Send the generic recare nudge SMS to the lapsed contact (best-effort, consent-gated). Returns whether
     * a nudge was actually sent ({@code false} for opt-out / no-phone / send-failure — the contact stays
     * ledgered for the period either way, so the next sweep does not re-spam; a re-engagement can still
     * happen when the contact books).
     */
    private Mono<Boolean> nudge(UUID tenantId, UUID contactId) {
        return contacts.findByTenantIdAndId(tenantId, contactId)
                .flatMap(contact -> {
                    if (hasOptedOut(contact)) {
                        log.debug("FD-2 recall: contact {} opted out — no nudge", contactId);
                        return Mono.just(false);
                    }
                    String phone = firstPhone(contact);
                    if (phone == null) {
                        log.debug("FD-2 recall: contact {} has no phone — no nudge", contactId);
                        return Mono.just(false);
                    }
                    SmsCommunicationRequest req = SmsCommunicationRequest.builder()
                            .to(new PhoneContact(phone))
                            .body(nudgeMessage)
                            .build();
                    return twilioSms.sendSms(req)
                            .doOnSuccess(ok -> log.debug("FD-2 recall: nudge SMS sent to contact {}", contactId))
                            .onErrorResume(err -> {
                                log.warn("FD-2 recall: nudge SMS failed for contact {} (continuing): {}",
                                        contactId, err.toString());
                                return Mono.just(false);
                            });
                })
                .defaultIfEmpty(false);
    }

    private Mono<UUID> resolveRecallSequenceId(UUID tenantId) {
        if (recallSequenceName.isBlank()) {
            return Mono.empty();
        }
        return sequences.findAllByTenantIdAndStatus(tenantId, Sequence.Status.ACTIVE)
                .filter(s -> recallSequenceName.equalsIgnoreCase(s.getName()))
                .next()
                .map(Sequence::getId);
    }

    private boolean hasOptedOut(Contact contact) {
        Set<String> tags = contact.getTags();
        return tags != null && tags.contains(FrontDeskConfirmationService.SMS_OPT_OUT_TAG);
    }

    private String firstPhone(Contact contact) {
        List<PhoneNumber> phones = contact.getPhones();
        if (phones == null) return null;
        for (PhoneNumber p : phones) {
            if (p != null && p.number() != null && !p.number().isBlank()) {
                return p.number();
            }
        }
        return null;
    }

    private String currentPeriodKey() {
        return ISO_WEEK.format(clock.instant().atZone(ZoneOffset.UTC));
    }
}
