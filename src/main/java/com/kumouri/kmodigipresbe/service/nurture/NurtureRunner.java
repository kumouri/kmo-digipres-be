package com.kumouri.kmodigipresbe.service.nurture;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityDirection;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCadenceStep;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCampaign;
import com.kumouri.kmodigipresbe.model.nurture.NurtureChannel;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollment;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollmentStatus;
import com.kumouri.kmodigipresbe.model.nurture.NurtureSendLog;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.module.chairfill.automation.RiskTieredPreventionService;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureCampaignRepository;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureEnrollmentRepository;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureSendLogRepository;
import com.kumouri.kmodigipresbe.service.ActivityCrudService;
import com.kumouri.kmodigipresbe.service.EmailService;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The default-OFF scheduled engine that drives every due {@link NurtureEnrollment} forward through its
 * {@link NurtureCampaign} cadence (E1 — Nurture / Cadence Engine). A faithful hybrid of the shipped
 * {@code CoverageNudgeJob} (default-OFF + ledger-insert-FIRST) and {@code SequenceEngine} (cross-tenant
 * poll → per-enrollment synthetic context).
 *
 * <h2>DEFAULT-OFF (the §2 directive — no live sends in CI / any default run)</h2>
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.nurture-runner", name="enabled",
 * matchIfMissing=false)} — the bean is not even created unless a deployment opts in, so the engine can
 * be administered (campaigns created, segmentation run, analytics read) while the runner stays OFF (the
 * {@code GbpReviewPoller} / {@code CoverageNudgeJob} precedent). This is a {@code @Component}, NOT a
 * {@code @Bean} in {@code NurtureAutoConfiguration}, so its OFF-by-default gate is independent of the
 * module's {@code matchIfMissing=true} gate.
 *
 * <h2>Per-step exactly-once — ledger-insert-FIRST, NEVER {@code switchIfEmpty(send)}</h2>
 * For each due enrollment's current step the runner runs an <strong>explicit-boolean</strong> probe is
 * unnecessary (the step advances monotonically) — instead it directly attempts the
 * {@link NurtureSendLog} insert FIRST (unique {@code tenant_enrollment_step_idx}) with
 * {@code onErrorResume(DuplicateKeyException → Mono.empty())}; a concurrent tick loses the insert and
 * sends ZERO duplicate touch — the money-grade {@code RecurringInvoiceOccurrence} pattern. A genuine
 * <em>transport</em> failure after the insert triggers a <strong>compensating delete</strong> of the
 * just-inserted ledger row (the {@code RecurringInvoiceSpawnService} precedent) so the step retries
 * cleanly next tick — "exactly-once" AND "never silently drop a send". An AI-personalize failure never
 * triggers the compensation (the composer already degraded to the template, which still sent).
 *
 * <h2>TCPA</h2>
 * A contact carrying the {@code sms-opt-out} tag → enrollment {@code OPTED_OUT} (never sent). A
 * per-contact rolling frequency cap (across ALL campaigns) <em>defers</em> (does not drop) a step that
 * would exceed {@code campaign.maxTouchesPerContactPerWindow}.
 *
 * <h2>§9 reactive</h2>
 * The {@code @Scheduled} tick fire-and-forget subscribes on the scheduler pool (never the Netty loop);
 * the visible-for-test {@link #runDueOnce()} returns a {@code Mono<Void>} the IT blocks; the cross-tenant
 * due query uses {@link ReactiveMongoTemplate} directly (the {@code SequenceEngine} precedent).
 */
@Slf4j
@org.springframework.stereotype.Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "kmosf.modules.nurture-runner", name = "enabled", matchIfMissing = false)
public class NurtureRunner {

    public static final String SYSTEM_ROLE = "NURTURE_RUNNER";

    private final ReactiveMongoTemplate mongo;
    private final NurtureCampaignRepository campaigns;
    private final NurtureEnrollmentRepository enrollments;
    private final NurtureSendLogRepository sendLogs;
    private final ContactRepository contacts;
    private final NurtureMessageComposer composer;
    private final TwilioSmsService twilioSmsService;
    private final EmailService emailService;
    private final ActivityCrudService activityCrudService;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final int frequencyWindowDays;
    private final String mailFromAddress;

    public NurtureRunner(ReactiveMongoTemplate mongo,
                         NurtureCampaignRepository campaigns,
                         NurtureEnrollmentRepository enrollments,
                         NurtureSendLogRepository sendLogs,
                         ContactRepository contacts,
                         NurtureMessageComposer composer,
                         TwilioSmsService twilioSmsService,
                         EmailService emailService,
                         ActivityCrudService activityCrudService,
                         DomainEventPublisher events,
                         ObjectProvider<Clock> clockProvider,
                         @Value("${kmosf.modules.nurture.frequency-window-days:7}") int frequencyWindowDays,
                         @Value("${kmosf.mail.smtp.username:}") String mailFromAddress) {
        this.mongo = mongo;
        this.campaigns = campaigns;
        this.enrollments = enrollments;
        this.sendLogs = sendLogs;
        this.contacts = contacts;
        this.composer = composer;
        this.twilioSmsService = twilioSmsService;
        this.emailService = emailService;
        this.activityCrudService = activityCrudService;
        this.events = events;
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
        this.frequencyWindowDays = frequencyWindowDays;
        this.mailFromAddress = mailFromAddress == null ? "" : mailFromAddress;
    }

    /**
     * Scheduled tick — fixed delay, default 5 min ({@code kmosf.modules.nurture-runner.interval-ms}).
     * Fire-and-forget subscribe on the scheduler thread (never the Netty loop).
     */
    @Scheduled(
            fixedDelayString = "${kmosf.modules.nurture-runner.interval-ms:300000}",
            initialDelayString = "${kmosf.modules.nurture-runner.initial-delay-ms:60000}")
    public void scheduledTick() {
        runDueOnce().subscribe(
                ignored -> {},
                err -> log.error("NurtureRunner tick failed", err));
    }

    /**
     * Visible-for-test entry — processes one full sweep of due enrollments across all tenants and
     * returns when done (the {@code SequenceEngine.runDueOnce} / {@code CoverageNudgeJob.nudgeDueOnce}
     * pattern). One step per due enrollment per sweep.
     */
    public Mono<Void> runDueOnce() {
        Instant now = clock.instant();
        Query q = new Query(new Criteria().andOperator(
                Criteria.where("status").in(
                        NurtureEnrollmentStatus.ACTIVE.name(), NurtureEnrollmentStatus.ENROLLED.name()),
                new Criteria().orOperator(
                        Criteria.where("nextFireAt").is(null),
                        Criteria.where("nextFireAt").lte(now))));
        return mongo.find(q, NurtureEnrollment.class)
                .concatMap(enr -> processEnrollment(enr)
                        .onErrorResume(err -> {
                            log.warn("Nurture enrollment {} failed in sweep: {}",
                                    enr.getId(), err.toString());
                            return Mono.empty();
                        }))
                .then();
    }

    private Mono<Void> processEnrollment(NurtureEnrollment enr) {
        UUID tenantId = enr.getTenantId();
        TenantContext ctx = new TenantContext(tenantId, null, Set.of(SYSTEM_ROLE));
        return campaigns.findByTenantIdAndId(tenantId, enr.getCampaignId())
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Nurture enrollment {} references missing campaign {} — exiting",
                            enr.getId(), enr.getCampaignId());
                    return exit(enr, "campaign missing").then(Mono.empty());
                }))
                .flatMap(campaign -> processStep(campaign, enr))
                .then()
                .contextWrite(TenantContextHolder.write(ctx));
    }

    private Mono<Void> processStep(NurtureCampaign campaign, NurtureEnrollment enr) {
        if (!campaign.isActive()) {
            return exit(enr, "campaign inactive");
        }
        List<NurtureCadenceStep> steps = campaign.getSteps() == null ? List.of() : campaign.getSteps();
        int idx = enr.getCurrentStepIndex();
        if (idx >= steps.size()) {
            return complete(enr);
        }
        NurtureCadenceStep step = stepAt(steps, idx);
        if (step == null) {
            // Defensive: a non-contiguous campaign — treat the missing index as completion.
            return complete(enr);
        }
        UUID tenantId = enr.getTenantId();
        return contacts.findByTenantIdAndId(tenantId, enr.getContactId())
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Nurture enrollment {} references missing contact {} — exiting",
                            enr.getId(), enr.getContactId());
                    return exit(enr, "contact missing").then(Mono.empty());
                }))
                .flatMap(contact -> {
                    if (isOptedOut(contact)) {
                        return optOut(enr);
                    }
                    String channelTarget = targetFor(step.channel(), contact);
                    if (channelTarget == null) {
                        // No reachable channel for this step — exit (a re-add of the channel re-enrolls).
                        return exit(enr, "no reachable " + step.channel() + " channel");
                    }
                    return enforceCapThen(campaign, enr, step, contact, channelTarget);
                });
    }

    /** TCPA frequency cap: defer (do not drop) when this contact is over the rolling window cap. */
    private Mono<Void> enforceCapThen(NurtureCampaign campaign, NurtureEnrollment enr,
                                      NurtureCadenceStep step, Contact contact, String channelTarget) {
        Instant windowStart = clock.instant().minus(frequencyWindowDays, ChronoUnit.DAYS);
        return sendLogs.countByTenantIdAndContactIdAndSentAtAfter(
                        enr.getTenantId(), contact.getId(), windowStart)
                .flatMap(count -> {
                    if (count >= campaign.getMaxTouchesPerContactPerWindow()) {
                        log.debug("Nurture cap reached for contact {} ({}/{}) — deferring step {}",
                                contact.getId(), count, campaign.getMaxTouchesPerContactPerWindow(),
                                step.stepIndex());
                        return deferToNextWindow(enr);
                    }
                    return ledgerFirstThenSend(campaign, enr, step, contact, channelTarget);
                });
    }

    /**
     * Ledger-insert-FIRST → compose → send → log Activity → advance. A concurrent tick loses the insert
     * (DuplicateKeyException → empty) = zero duplicate. A transport failure compensating-deletes the
     * row and does NOT advance (retry next tick). NEVER {@code switchIfEmpty(send)}.
     */
    private Mono<Void> ledgerFirstThenSend(NurtureCampaign campaign, NurtureEnrollment enr,
                                           NurtureCadenceStep step, Contact contact, String channelTarget) {
        NurtureSendLog log0 = NurtureSendLog.builder()
                .tenantId(enr.getTenantId())
                .enrollmentId(enr.getId())
                .stepIndex(step.stepIndex())
                .channel(step.channel())
                .contactId(contact.getId())
                .sentAt(clock.instant())
                .build();
        return sendLogs.save(log0)
                .onErrorResume(DuplicateKeyException.class, e -> {
                    log.debug("Nurture concurrent-fire lost ledger insert for enrollment {} step {} "
                            + "— zero duplicate", enr.getId(), step.stepIndex());
                    return Mono.empty();
                })
                .flatMap(savedLog -> composer.compose(step, contact)
                        .flatMap(msg -> dispatch(step.channel(), channelTarget, msg)
                                .then(persistLedgerAiFlag(savedLog, msg.aiApplied()))
                                .then(logTouchActivity(enr, contact, step, msg))
                                .then(advanceAfterSend(campaign, enr, step, contact, msg)))
                        // A transport (send) failure: compensating-delete the ledger row so the step
                        // retries cleanly next tick; do NOT advance. (AI failure never reaches here —
                        // the composer already degraded to the template, which sent.)
                        .onErrorResume(sendErr -> sendLogs.delete(savedLog)
                                .then(Mono.fromRunnable(() -> log.warn(
                                        "Nurture step {} send failed for enrollment {} — ledger row "
                                                + "compensated, will retry: {}",
                                        step.stepIndex(), enr.getId(), sendErr.toString())))
                                .then()));
    }

    private Mono<Void> dispatch(NurtureChannel channel, String target,
                                NurtureMessageComposer.ComposedMessage msg) {
        if (channel == NurtureChannel.SMS) {
            return twilioSmsService.sendSms(SmsCommunicationRequest.builder()
                            .to(new PhoneContact(target))
                            .body(msg.body())
                            .build())
                    .then();
        }
        // EMAIL
        if (mailFromAddress.isBlank()) {
            return Mono.error(new IllegalStateException(
                    "kmosf.mail.smtp.username (email from) is not configured for nurture email"));
        }
        return emailService.sendSingleEmail(SingleEmailCommunicationRequest.builder()
                        .from(new EmailContact(mailFromAddress))
                        .to(new EmailContact(target))
                        .subject(msg.subject())
                        .body(msg.body())
                        .build())
                .then();
    }

    private Mono<Void> persistLedgerAiFlag(NurtureSendLog savedLog, boolean aiApplied) {
        if (!aiApplied) {
            return Mono.empty();
        }
        return sendLogs.save(savedLog.toBuilder().aiPersonalizedApplied(true).build()).then();
    }

    /** Best-effort timeline note (an Activity write failure must not fail the already-sent touch). */
    private Mono<Void> logTouchActivity(NurtureEnrollment enr, Contact contact,
                                        NurtureCadenceStep step, NurtureMessageComposer.ComposedMessage msg) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("campaignId", enr.getCampaignId().toString());
        payload.put("enrollmentId", enr.getId().toString());
        payload.put("stepIndex", step.stepIndex());
        payload.put("channel", step.channel().name());
        payload.put("aiApplied", msg.aiApplied());
        String summary = "Nurture touch (" + step.channel() + ", step " + step.stepIndex() + ")";
        Activity activity = Activity.builder()
                .tenantId(enr.getTenantId())
                .type(ActivityType.NOTE)
                .direction(ActivityDirection.OUTBOUND)
                .subjectType(SubjectType.CONTACT)
                .subjectId(contact.getId())
                .summary(summary)
                .body(step.channel() == NurtureChannel.EMAIL ? msg.subject() : msg.body())
                .occurredAt(clock.instant())
                .payload(payload)
                .build();
        return activityCrudService.create(activity)
                .onErrorResume(e -> {
                    log.warn("Nurture touch Activity log failed (best-effort, ignored): {}", e.toString());
                    return Mono.empty();
                })
                .then();
    }

    /**
     * Advance the enrollment after a successful send: bump the step index + accumulated backoff,
     * compute the next {@code nextFireAt} from the next step's {@code offsetDays + appliedBackoffDays}
     * (or COMPLETE if past the last step), set status ACTIVE, save; then emit NURTURE_TOUCH_SENT.
     */
    private Mono<Void> advanceAfterSend(NurtureCampaign campaign, NurtureEnrollment enr,
                                        NurtureCadenceStep step, Contact contact,
                                        NurtureMessageComposer.ComposedMessage msg) {
        List<NurtureCadenceStep> steps = campaign.getSteps();
        int nextIdx = enr.getCurrentStepIndex() + 1;
        int newBackoff = enr.getAppliedBackoffDays() + Math.max(0, step.backoffDays());
        Instant now = clock.instant();
        NurtureEnrollment.NurtureEnrollmentBuilder b = enr.toBuilder()
                .currentStepIndex(nextIdx)
                .appliedBackoffDays(newBackoff)
                .lastTouchAt(now);
        if (nextIdx >= steps.size()) {
            b.status(NurtureEnrollmentStatus.COMPLETED).nextFireAt(null);
        } else {
            NurtureCadenceStep nextStep = stepAt(steps, nextIdx);
            int gapDays = (nextStep == null ? 0 : Math.max(0, nextStep.offsetDays())) + newBackoff;
            b.status(NurtureEnrollmentStatus.ACTIVE)
                    .nextFireAt(now.plus(gapDays, ChronoUnit.DAYS));
        }
        return enrollments.save(b.build())
                .doOnNext(saved -> events.publish(DomainEvent.of(
                        DomainEventType.NURTURE_TOUCH_SENT, enr.getTenantId(), enr.getId(),
                        touchPayload(enr, contact, step, msg))))
                .then();
    }

    private Mono<Void> deferToNextWindow(NurtureEnrollment enr) {
        Instant next = clock.instant().plus(frequencyWindowDays, ChronoUnit.DAYS);
        return enrollments.save(enr.toBuilder()
                .status(NurtureEnrollmentStatus.ACTIVE)
                .nextFireAt(next)
                .build()).then();
    }

    private Mono<Void> exit(NurtureEnrollment enr, String reason) {
        return enrollments.save(enr.toBuilder()
                .status(NurtureEnrollmentStatus.EXITED)
                .exitedReason(reason)
                .nextFireAt(null)
                .build()).then();
    }

    private Mono<Void> optOut(NurtureEnrollment enr) {
        return enrollments.save(enr.toBuilder()
                .status(NurtureEnrollmentStatus.OPTED_OUT)
                .nextFireAt(null)
                .build()).then();
    }

    private Mono<Void> complete(NurtureEnrollment enr) {
        return enrollments.save(enr.toBuilder()
                .status(NurtureEnrollmentStatus.COMPLETED)
                .nextFireAt(null)
                .build()).then();
    }

    private static NurtureCadenceStep stepAt(List<NurtureCadenceStep> steps, int idx) {
        for (NurtureCadenceStep s : steps) {
            if (s.stepIndex() == idx) {
                return s;
            }
        }
        // Fallback to positional if stepIndex wasn't set explicitly contiguous.
        return (idx >= 0 && idx < steps.size()) ? steps.get(idx) : null;
    }

    /** The reachable target for the step's channel (first non-blank phone/email), or null. */
    private static String targetFor(NurtureChannel channel, Contact contact) {
        if (channel == NurtureChannel.SMS) {
            List<PhoneNumber> phones = contact.getPhones();
            if (phones != null) {
                for (PhoneNumber p : phones) {
                    if (p != null && p.number() != null && !p.number().isBlank()) {
                        return p.number();
                    }
                }
            }
            return null;
        }
        // EMAIL
        List<EmailContact> emails = contact.getEmails();
        if (emails != null) {
            for (EmailContact e : emails) {
                if (e != null && e.asString() != null && !e.asString().isBlank()) {
                    return e.asString();
                }
            }
        }
        return null;
    }

    private static boolean isOptedOut(Contact contact) {
        Set<String> tags = contact.getTags();
        return tags != null && tags.contains(RiskTieredPreventionService.SMS_OPT_OUT_TAG);
    }

    private static Map<String, Object> touchPayload(NurtureEnrollment enr, Contact contact,
                                                    NurtureCadenceStep step,
                                                    NurtureMessageComposer.ComposedMessage msg) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("campaignId", enr.getCampaignId().toString());
        payload.put("enrollmentId", enr.getId().toString());
        payload.put("contactId", contact.getId().toString());
        payload.put("stepIndex", step.stepIndex());
        payload.put("channel", step.channel().name());
        payload.put("aiApplied", msg.aiApplied());
        return payload;
    }
}
