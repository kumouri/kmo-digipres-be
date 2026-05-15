package com.kumouri.kmodigipresbe.service.servicehub;

import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.contact.Company;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import com.kumouri.kmodigipresbe.model.servicehub.HealthScore;
import com.kumouri.kmodigipresbe.model.servicehub.TicketPriority;
import com.kumouri.kmodigipresbe.model.servicehub.TicketStatus;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.DealRepository;
import com.kumouri.kmodigipresbe.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Nightly health-score compute. Evaluates four fixed rules per contact and
 * writes the result back in-place. Phase 11 can extend the rule set or
 * swap to ML-derived scores without changing the embedded {@link HealthScore}
 * schema.
 *
 * <p>Rules (v1):
 * <ul>
 *   <li>no-activity-30d  → -20 pts</li>
 *   <li>no-open-ticket-30d → -15 pts (no open ticket created in last 30d)</li>
 *   <li>urgent-open-ticket → -25 pts (any URGENT ticket in NEW/OPEN/PENDING)</li>
 *   <li>recent-deal-won  → +30 pts (WON deal updated in last 30d)</li>
 * </ul>
 * Base score is 70; all adjustments are additive.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthScoreService {

    private static final int BASE_SCORE = 70;
    private static final List<TicketStatus> OPEN_STATUSES = List.of(
            TicketStatus.NEW, TicketStatus.OPEN, TicketStatus.PENDING);

    private final ContactRepository contacts;
    private final ActivityRepository activities;
    private final TicketRepository tickets;
    private final DealRepository deals;
    private final ReactiveMongoTemplate mongo;

    @Scheduled(cron = "0 0 2 * * *")
    public void computeNightly() {
        computeAll().subscribe(
                count -> log.info("Health-score nightly run complete: {} contacts scored", count),
                err -> log.error("Health-score nightly run failed", err));
    }

    public Mono<Long> computeAll() {
        return mongo.findAll(Contact.class)
                .flatMap(c -> scoreContact(c)
                        .flatMap(hs -> mongo.updateFirst(
                                Query.query(Criteria.where("_id").is(c.getId())),
                                new Update().set("healthScore", hs),
                                Contact.class))
                        .thenReturn(1L))
                .reduce(0L, Long::sum);
    }

    public Mono<HealthScore> scoreContact(Contact contact) {
        UUID tenantId = contact.getTenantId();
        UUID contactId = contact.getId();
        Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);

        return Mono.zip(
                activities.existsByTenantIdAndSubjectTypeAndSubjectIdAndOccurredAtAfter(
                        tenantId, SubjectType.CONTACT, contactId, thirtyDaysAgo),
                tickets.existsByTenantIdAndContactIdAndStatusIn(
                        tenantId, contactId, OPEN_STATUSES),
                tickets.existsByTenantIdAndContactIdAndPriorityAndStatusIn(
                        tenantId, contactId, TicketPriority.URGENT, OPEN_STATUSES),
                deals.existsByTenantIdAndPrimaryContactIdAndStageAndUpdatedAtAfter(
                        tenantId, contactId, PipelineStage.WON, thirtyDaysAgo)
        ).map(tuple -> {
            boolean hasRecentActivity = tuple.getT1();
            boolean hasOpenTicket = tuple.getT2();
            boolean hasUrgentTicket = tuple.getT3();
            boolean hasRecentWon = tuple.getT4();

            List<String> drivers = new ArrayList<>();
            int delta = 0;

            if (!hasRecentActivity) {
                delta -= 20;
                drivers.add("no-activity-30d");
            }
            if (!hasOpenTicket) {
                delta -= 15;
                drivers.add("no-open-ticket-30d");
            }
            if (hasUrgentTicket) {
                delta -= 25;
                drivers.add("urgent-open-ticket");
            }
            if (hasRecentWon) {
                delta += 30;
                drivers.add("recent-deal-won");
            }

            return HealthScore.of(BASE_SCORE + delta, drivers);
        });
    }

    public Mono<HealthScore> getContactScore(UUID tenantId, UUID contactId) {
        return contacts.findByTenantIdAndId(tenantId, contactId)
                .flatMap(contact -> {
                    if (contact.getHealthScore() != null) {
                        return Mono.just(contact.getHealthScore());
                    }
                    return scoreContact(contact)
                            .flatMap(hs -> mongo.updateFirst(
                                    Query.query(Criteria.where("_id").is(contactId)),
                                    new Update().set("healthScore", hs),
                                    Contact.class).thenReturn(hs));
                });
    }

    public Mono<HealthScore> getCompanyScore(UUID tenantId, UUID companyId) {
        return mongo.findOne(
                Query.query(Criteria.where("_id").is(companyId).and("tenantId").is(tenantId)),
                Company.class
        ).flatMap(company -> {
            if (company.getHealthScore() != null) {
                return Mono.just(company.getHealthScore());
            }
            // Company score: aggregate from all contacts in the company
            return contacts.findAllByTenantIdAndCompanyId(tenantId, companyId)
                    .flatMap(this::scoreContact)
                    .collectList()
                    .map(scores -> {
                        if (scores.isEmpty()) {
                            return HealthScore.of(BASE_SCORE, List.of());
                        }
                        int avg = (int) scores.stream().mapToInt(HealthScore::score).average().orElse(BASE_SCORE);
                        return HealthScore.of(avg, List.of("aggregated-from-contacts"));
                    })
                    .flatMap(hs -> mongo.updateFirst(
                            Query.query(Criteria.where("_id").is(companyId)),
                            new Update().set("healthScore", hs),
                            Company.class).thenReturn(hs));
        });
    }
}
