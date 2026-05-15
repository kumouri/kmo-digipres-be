package com.kumouri.kmodigipresbe.service.ai.scoring;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.ai.LeadScoringJob;
import com.kumouri.kmodigipresbe.model.communication.EmailEngagement;
import com.kumouri.kmodigipresbe.model.communication.EmailEngagementEvent;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import com.kumouri.kmodigipresbe.model.scoring.LeadScore;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.DealRepository;
import com.kumouri.kmodigipresbe.repository.EmailEngagementRepository;
import com.kumouri.kmodigipresbe.repository.LeadScoringJobRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import smile.classification.LogisticRegression;
import smile.classification.SoftClassifier;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LeadScoringV2Service {

    private static final int MIN_CLOSED_DEALS_FOR_MODEL = 50;

    private final TenantRepository tenantRepository;
    private final ContactRepository contactRepository;
    private final DealRepository dealRepository;
    private final ActivityRepository activityRepository;
    private final EmailEngagementRepository emailEngagementRepository;
    private final LeadScoringJobRepository jobRepository;
    private final DomainEventPublisher eventPublisher;

    @Scheduled(cron = "${kmosf.lead-scoring.nightly-cron:0 0 2 * * *}")
    public void nightlyRun() {
        tenantRepository.findAll()
                .filter(t -> t.getStatus() == Tenant.TenantStatus.ACTIVE)
                .flatMap(tenant -> {
                    LeadScoringJob job = LeadScoringJob.builder()
                            .id(UUID.randomUUID())
                            .tenantId(tenant.getId())
                            .status(LeadScoringJob.JobStatus.PENDING)
                            .build();
                    return jobRepository.save(job)
                            .flatMap(saved -> runJobForTenant(tenant.getId(), saved))
                            .onErrorResume(err -> {
                                log.warn("Nightly lead-scoring failed for tenant {}: {}",
                                        tenant.getId(), err.getMessage());
                                return Mono.empty();
                            });
                })
                .subscribe();
    }

    public Mono<LeadScoringJob> triggerRetrain(UUID tenantId) {
        return jobRepository
                .findFirstByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, LeadScoringJob.JobStatus.RUNNING)
                .flatMap(existing -> Mono.<LeadScoringJob>error(
                        new DigiPresBeException("A retrain job is already running for this tenant", 3001, 409)))
                .switchIfEmpty(Mono.defer(() -> {
                    LeadScoringJob job = LeadScoringJob.builder()
                            .id(UUID.randomUUID())
                            .tenantId(tenantId)
                            .status(LeadScoringJob.JobStatus.PENDING)
                            .build();
                    return jobRepository.save(job)
                            .flatMap(saved -> {
                                runJobForTenant(tenantId, saved)
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .subscribe(
                                                null,
                                                err -> log.warn("Manual retrain failed for tenant {}: {}",
                                                        tenantId, err.getMessage()));
                                return Mono.just(saved);
                            });
                }));
    }

    public Mono<Void> runJobForTenant(UUID tenantId, LeadScoringJob job) {
        Mono<LeadScoringJob> markRunning = jobRepository.save(job.toBuilder()
                .status(LeadScoringJob.JobStatus.RUNNING)
                .startedAt(Instant.now())
                .build());

        Instant since30d = Instant.now().minus(30, ChronoUnit.DAYS);

        Mono<List<Contact>> contactsMono = contactRepository.findAllByTenantId(tenantId).collectList();
        Mono<List<Deal>> dealsMono = dealRepository.findAllByTenantId(tenantId).collectList();
        Mono<List<com.kumouri.kmodigipresbe.model.activity.Activity>> activListMono = activityRepository
                .findAllByTenantIdAndSubjectTypeAndOccurredAtAfter(tenantId, SubjectType.CONTACT, since30d)
                .collectList();
        Mono<List<EmailEngagement>> engagementsMono = emailEngagementRepository
                .findAllByTenantId(tenantId).collectList();

        return markRunning
                .then(Mono.zip(contactsMono, dealsMono, activListMono, engagementsMono))
                .flatMap(t -> Mono.fromCallable(
                        () -> scoreAll(t.getT1(), t.getT2(), t.getT3(), t.getT4()))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMapMany(Flux::fromIterable)
                .flatMap(contact -> contactRepository.save(contact)
                        .doOnSuccess(saved -> {
                            if (saved.getLeadScore() != null) {
                                eventPublisher.publish(DomainEvent.of(
                                        DomainEventType.LEAD_SCORE_UPDATED,
                                        tenantId, saved.getId(),
                                        Map.of("contactId", saved.getId().toString(),
                                                "score", saved.getLeadScore().score(),
                                                "tier", saved.getLeadScore().tier(),
                                                "source", saved.getLeadScore().source())));
                            }
                        }))
                .count()
                .flatMap(count -> jobRepository.save(job.toBuilder()
                        .status(LeadScoringJob.JobStatus.DONE)
                        .contactsScored((int) (long) count)
                        .completedAt(Instant.now())
                        .build()))
                .onErrorResume(err -> {
                    log.error("Lead-scoring job {} failed: {}", job.getId(), err.getMessage(), err);
                    return jobRepository.save(job.toBuilder()
                            .status(LeadScoringJob.JobStatus.FAILED)
                            .errorMessage(err.getMessage())
                            .completedAt(Instant.now())
                            .build());
                })
                .then();
    }

    private List<Contact> scoreAll(
            List<Contact> contacts,
            List<Deal> deals,
            List<com.kumouri.kmodigipresbe.model.activity.Activity> recentActivities,
            List<EmailEngagement> engagements) {

        Instant since7d = Instant.now().minus(7, ChronoUnit.DAYS);

        Map<UUID, List<Deal>> dealsByContact = deals.stream()
                .filter(d -> d.getPrimaryContactId() != null)
                .collect(Collectors.groupingBy(Deal::getPrimaryContactId));
        Map<UUID, List<com.kumouri.kmodigipresbe.model.activity.Activity>> activities30dByContact =
                recentActivities.stream()
                        .filter(a -> a.getSubjectId() != null)
                        .collect(Collectors.groupingBy(
                                com.kumouri.kmodigipresbe.model.activity.Activity::getSubjectId));
        Map<UUID, List<EmailEngagement>> engagementsByContact = engagements.stream()
                .filter(e -> e.getContactId() != null)
                .collect(Collectors.groupingBy(EmailEngagement::getContactId));

        // Build training set from contacts with unambiguous closed-deal outcomes
        List<double[]> trainingFeatures = new ArrayList<>();
        List<Integer> trainingLabels = new ArrayList<>();

        for (Contact contact : contacts) {
            List<Deal> cd = dealsByContact.getOrDefault(contact.getId(), List.of());
            boolean hasWon = cd.stream().anyMatch(d -> d.getStage() == PipelineStage.WON);
            boolean hasLost = cd.stream().anyMatch(d -> d.getStage() == PipelineStage.LOST);
            if (hasWon && !hasLost) {
                trainingFeatures.add(features(contact, cd, activities30dByContact, engagementsByContact, since7d));
                trainingLabels.add(1);
            } else if (hasLost && !hasWon) {
                trainingFeatures.add(features(contact, cd, activities30dByContact, engagementsByContact, since7d));
                trainingLabels.add(0);
            }
        }

        final SoftClassifier<double[]> model = trainingFeatures.size() >= MIN_CLOSED_DEALS_FOR_MODEL
                ? trainModel(trainingFeatures, trainingLabels)
                : null;

        if (model != null) {
            log.info("Lead-scoring: trained LogisticRegression on {} samples for tenant", trainingFeatures.size());
        } else {
            log.info("Lead-scoring: {} closed-deal samples — using rules fallback (need {})",
                    trainingFeatures.size(), MIN_CLOSED_DEALS_FOR_MODEL);
        }

        return contacts.stream()
                .map(contact -> {
                    List<Deal> cd = dealsByContact.getOrDefault(contact.getId(), List.of());
                    double[] f = features(contact, cd, activities30dByContact, engagementsByContact, since7d);
                    LeadScore score = model != null ? scoreWithModel(model, f) : scoreWithRules(f);
                    return contact.toBuilder().leadScore(score).build();
                })
                .collect(Collectors.toList());
    }

    private double[] features(
            Contact contact,
            List<Deal> contactDeals,
            Map<UUID, List<com.kumouri.kmodigipresbe.model.activity.Activity>> activities30dByContact,
            Map<UUID, List<EmailEngagement>> engagementsByContact,
            Instant since7d) {

        List<com.kumouri.kmodigipresbe.model.activity.Activity> acts =
                activities30dByContact.getOrDefault(contact.getId(), List.of());
        long a7d = acts.stream()
                .filter(a -> a.getOccurredAt() != null && a.getOccurredAt().isAfter(since7d))
                .count();

        List<EmailEngagement> engs = engagementsByContact.getOrDefault(contact.getId(), List.of());
        long opens = engs.stream().filter(e -> e.getEvent() == EmailEngagementEvent.OPEN).count();
        long clicks = engs.stream().filter(e -> e.getEvent() == EmailEngagementEvent.CLICK).count();
        long delivered = engs.stream().filter(e -> e.getEvent() == EmailEngagementEvent.DELIVERED).count();
        double openRate = delivered > 0 ? (double) opens / delivered : 0.0;
        double clickRate = delivered > 0 ? (double) clicks / delivered : 0.0;
        double firmographic = contact.getCompanyId() != null ? 1.0 : 0.5;

        return new double[]{(double) a7d, (double) acts.size(), openRate, clickRate,
                (double) contactDeals.size(), firmographic};
    }

    private SoftClassifier<double[]> trainModel(List<double[]> featuresList, List<Integer> labels) {
        double[][] x = featuresList.toArray(new double[0][]);
        int[] y = labels.stream().mapToInt(Integer::intValue).toArray();
        // 0 = LOST, 1 = WON; posterior[1] is the WON probability used as score
        return LogisticRegression.fit(x, y);
    }

    private LeadScore scoreWithModel(SoftClassifier<double[]> model, double[] f) {
        double[] posterior = new double[2];
        model.predict(f, posterior);
        double score = posterior[1];
        return new LeadScore(score, LeadScore.tierFrom(score), LeadScore.SOURCE_MODEL, Instant.now());
    }

    private LeadScore scoreWithRules(double[] f) {
        double a7d = f[0], a30d = f[1], openRate = f[2];
        if (a30d == 0 && openRate == 0.0) {
            return new LeadScore(0.0, LeadScore.TIER_COLD, LeadScore.SOURCE_INSUFFICIENT_DATA, Instant.now());
        }
        if (a7d >= 3 || openRate >= 0.3) {
            return new LeadScore(0.8, LeadScore.TIER_HOT, LeadScore.SOURCE_RULES_FALLBACK, Instant.now());
        }
        if (a30d >= 1 || openRate > 0) {
            return new LeadScore(0.5, LeadScore.TIER_WARM, LeadScore.SOURCE_RULES_FALLBACK, Instant.now());
        }
        return new LeadScore(0.2, LeadScore.TIER_COLD, LeadScore.SOURCE_RULES_FALLBACK, Instant.now());
    }
}
