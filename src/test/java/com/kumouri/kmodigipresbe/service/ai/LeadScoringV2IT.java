package com.kumouri.kmodigipresbe.service.ai;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.ai.LeadScoringJob;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import com.kumouri.kmodigipresbe.model.scoring.LeadScore;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.DealRepository;
import com.kumouri.kmodigipresbe.repository.LeadScoringJobRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.service.ai.scoring.LeadScoringV2Service;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link LeadScoringV2Service}.
 *
 * <ul>
 *   <li>Tenant with ≥50 closed deals trains a RandomForest model and produces
 *       {@code source="MODEL"} scores.</li>
 *   <li>Tenant with fewer than 50 closed deals falls back to the rules-based
 *       scorer ({@code source="RULES_FALLBACK"} or {@code INSUFFICIENT_DATA}).</li>
 *   <li>Score values are deterministic across two consecutive runs.</li>
 * </ul>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LeadScoringV2IT {

    @Autowired LeadScoringV2Service scoringService;
    @Autowired ContactRepository contactRepository;
    @Autowired DealRepository dealRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired LeadScoringJobRepository jobRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired ReactiveMongoTemplate mongo;

    @BeforeEach
    void cleanup() {
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), Deal.class).block();
        mongo.remove(new Query(), Activity.class).block();
        mongo.remove(new Query(), LeadScoringJob.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    @Test
    void tenantWith60ClosedDeals_scoresUseModel() {
        UUID tenantId = UUID.randomUUID();
        seedTenant(tenantId);

        List<UUID> wonContactIds = new ArrayList<>();
        List<UUID> lostContactIds = new ArrayList<>();

        // Seed 35 WON and 25 LOST deals, each with a distinct contact
        for (int i = 0; i < 35; i++) {
            UUID cid = seedContact(tenantId);
            seedDeal(tenantId, cid, PipelineStage.WON);
            seedActivities(tenantId, cid, 4, 3); // 4 in last 30d, 3 in last 7d
            wonContactIds.add(cid);
        }
        for (int i = 0; i < 25; i++) {
            UUID cid = seedContact(tenantId);
            seedDeal(tenantId, cid, PipelineStage.LOST);
            // minimal activity — cold signal
            lostContactIds.add(cid);
        }

        LeadScoringJob job = LeadScoringJob.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .status(LeadScoringJob.JobStatus.PENDING).build();
        jobRepository.save(job).block();

        scoringService.runJobForTenant(tenantId, job).block();

        // All contacts should now have a score with source=MODEL
        wonContactIds.forEach(cid -> {
            Contact contact = contactRepository.findByTenantIdAndId(tenantId, cid).block();
            assertThat(contact).isNotNull();
            assertThat(contact.getLeadScore()).isNotNull();
            assertThat(contact.getLeadScore().source()).isEqualTo(LeadScore.SOURCE_MODEL);
        });
        lostContactIds.forEach(cid -> {
            Contact contact = contactRepository.findByTenantIdAndId(tenantId, cid).block();
            assertThat(contact.getLeadScore()).isNotNull();
            assertThat(contact.getLeadScore().source()).isEqualTo(LeadScore.SOURCE_MODEL);
        });
    }

    @Test
    void tenantWith10ClosedDeals_scoresUseRulesFallback() {
        UUID tenantId = UUID.randomUUID();
        seedTenant(tenantId);

        List<UUID> contactIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID cid = seedContact(tenantId);
            seedDeal(tenantId, cid, i % 2 == 0 ? PipelineStage.WON : PipelineStage.LOST);
            contactIds.add(cid);
        }
        // One contact with lots of recent activity
        UUID activeContact = seedContact(tenantId);
        seedActivities(tenantId, activeContact, 5, 4);
        contactIds.add(activeContact);

        LeadScoringJob job = LeadScoringJob.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .status(LeadScoringJob.JobStatus.PENDING).build();
        jobRepository.save(job).block();

        scoringService.runJobForTenant(tenantId, job).block();

        // All contacts should have rules-fallback or insufficient-data scores (not MODEL)
        for (UUID cid : contactIds) {
            Contact contact = contactRepository.findByTenantIdAndId(tenantId, cid).block();
            assertThat(contact.getLeadScore()).isNotNull();
            assertThat(contact.getLeadScore().source())
                    .isIn(LeadScore.SOURCE_RULES_FALLBACK, LeadScore.SOURCE_INSUFFICIENT_DATA);
        }

        // The contact with 4 activities in the last 7 days should be HOT
        Contact active = contactRepository.findByTenantIdAndId(tenantId, activeContact).block();
        assertThat(active.getLeadScore().tier()).isEqualTo(LeadScore.TIER_HOT);
    }

    @Test
    void triggerRetrain_storesJobAndScoresAsync() {
        UUID tenantId = UUID.randomUUID();
        seedTenant(tenantId);
        UUID contactId = seedContact(tenantId);
        seedActivities(tenantId, contactId, 2, 0);

        LeadScoringJob returned = scoringService.triggerRetrain(tenantId).block();
        assertThat(returned).isNotNull();
        assertThat(returned.getStatus()).isEqualTo(LeadScoringJob.JobStatus.PENDING);

        // Wait for async job to complete
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Contact contact = contactRepository.findByTenantIdAndId(tenantId, contactId).block();
            assertThat(contact.getLeadScore()).isNotNull();
        });
    }

    @Test
    void scoresAreStableAcrossConsecutiveRuns() {
        UUID tenantId = UUID.randomUUID();
        seedTenant(tenantId);
        UUID contactId = seedContact(tenantId);
        seedActivities(tenantId, contactId, 3, 2);

        LeadScoringJob job1 = LeadScoringJob.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .status(LeadScoringJob.JobStatus.PENDING).build();
        jobRepository.save(job1).block();
        scoringService.runJobForTenant(tenantId, job1).block();

        Contact first = contactRepository.findByTenantIdAndId(tenantId, contactId).block();
        assertThat(first.getLeadScore()).isNotNull();
        double firstScore = first.getLeadScore().score();

        LeadScoringJob job2 = LeadScoringJob.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .status(LeadScoringJob.JobStatus.PENDING).build();
        jobRepository.save(job2).block();
        scoringService.runJobForTenant(tenantId, job2).block();

        Contact second = contactRepository.findByTenantIdAndId(tenantId, contactId).block();
        // With rules-based fallback (< 50 closed deals), score is deterministic
        assertThat(second.getLeadScore().score()).isEqualTo(firstScore);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void seedTenant(UUID tenantId) {
        tenantRepository.save(Tenant.builder()
                .id(tenantId).slug("scoring-" + tenantId)
                .displayName("Scoring Test").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(BigDecimal.ZERO).build()).block();
    }

    private UUID seedContact(UUID tenantId) {
        UUID id = UUID.randomUUID();
        contactRepository.save(Contact.builder().id(id).tenantId(tenantId)
                .firstName("Test").lastName("Contact " + id).build()).block();
        return id;
    }

    private void seedDeal(UUID tenantId, UUID contactId, PipelineStage stage) {
        dealRepository.save(Deal.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .title("Deal for " + contactId)
                .primaryContactId(contactId)
                .stage(stage)
                .value(BigDecimal.valueOf(1000)).build()).block();
    }

    private void seedActivities(UUID tenantId, UUID contactId, int total30d, int within7d) {
        Instant now = Instant.now();
        for (int i = 0; i < total30d; i++) {
            Instant when = i < within7d
                    ? now.minus(java.time.Duration.ofDays(1))
                    : now.minus(java.time.Duration.ofDays(15));
            activityRepository.save(Activity.builder()
                    .id(UUID.randomUUID()).tenantId(tenantId)
                    .type(ActivityType.NOTE)
                    .subjectType(SubjectType.CONTACT).subjectId(contactId)
                    .summary("Note " + i).occurredAt(when).build()).block();
        }
    }
}
