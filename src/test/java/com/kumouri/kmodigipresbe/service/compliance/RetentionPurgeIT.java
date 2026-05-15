package com.kumouri.kmodigipresbe.service.compliance;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.RuleCondition;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.compliance.RetentionPolicy;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.repository.RetentionPolicyRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link RetentionPolicyService#runPurge()}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class RetentionPurgeIT {

    @Autowired RetentionPolicyService retentionPolicyService;
    @Autowired RetentionPolicyRepository retentionPolicyRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;

    @BeforeEach
    void setup() {
        mongo.remove(new Query(), Activity.class).block();
        mongo.remove(new Query(), RetentionPolicy.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenantRepository.save(Tenant.builder()
                .id(tenantId).slug("retention-" + tenantId)
                .displayName("Retention Test").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(BigDecimal.ZERO).build()).block();
    }

    @Test
    void activityOlderThanWindow_isDeleted() {
        // Save a 30-day retention policy
        retentionPolicyRepository.save(RetentionPolicy.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .entityType("Activity").retentionDays(30)
                .exceptions(List.of()).build()).block();

        // Seed an Activity that is 31 days old
        UUID oldActivityId = UUID.randomUUID();
        Activity old = activityRepository.save(Activity.builder()
                .id(oldActivityId).tenantId(tenantId)
                .type(ActivityType.NOTE).subjectType(SubjectType.CONTACT)
                .subjectId(UUID.randomUUID()).summary("Old note")
                .customFields(Map.of()).build()).block();

        // Back-date createdAt by overwriting via template
        mongo.save(old.toBuilder()
                .createdAt(Instant.now().minus(31, ChronoUnit.DAYS)).build()).block();

        // Seed a recent Activity (should survive)
        UUID recentId = UUID.randomUUID();
        activityRepository.save(Activity.builder()
                .id(recentId).tenantId(tenantId)
                .type(ActivityType.NOTE).subjectType(SubjectType.CONTACT)
                .subjectId(UUID.randomUUID()).summary("Recent note")
                .customFields(Map.of()).build()).block();

        retentionPolicyService.runPurge().block();

        assertThat(mongo.findById(oldActivityId, Activity.class).block()).isNull();
        assertThat(mongo.findById(recentId, Activity.class).block()).isNotNull();
    }

    @Test
    void activityWithLegalHold_survivesRetentionPurge() {
        // Policy with exception: skip activities where customFields.legalHold == "true"
        RuleCondition legalHoldCondition = new RuleCondition();
        legalHoldCondition.setField("legalHold");
        legalHoldCondition.setOp(RuleCondition.Op.EQUALS);
        legalHoldCondition.setValue("true");

        retentionPolicyRepository.save(RetentionPolicy.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .entityType("Activity").retentionDays(30)
                .exceptions(List.of(legalHoldCondition)).build()).block();

        // Old Activity WITHOUT legal hold — should be purged
        UUID purgeable = UUID.randomUUID();
        Activity purgeableActivity = activityRepository.save(Activity.builder()
                .id(purgeable).tenantId(tenantId)
                .type(ActivityType.NOTE).subjectType(SubjectType.CONTACT)
                .subjectId(UUID.randomUUID()).summary("Will be purged")
                .customFields(Map.of()).build()).block();
        mongo.save(purgeableActivity.toBuilder()
                .createdAt(Instant.now().minus(31, ChronoUnit.DAYS)).build()).block();

        // Old Activity WITH legal hold — should survive
        UUID legalHold = UUID.randomUUID();
        Activity legalHoldActivity = activityRepository.save(Activity.builder()
                .id(legalHold).tenantId(tenantId)
                .type(ActivityType.NOTE).subjectType(SubjectType.CONTACT)
                .subjectId(UUID.randomUUID()).summary("On legal hold")
                .customFields(Map.of("legalHold", "true")).build()).block();
        mongo.save(legalHoldActivity.toBuilder()
                .createdAt(Instant.now().minus(31, ChronoUnit.DAYS)).build()).block();

        retentionPolicyService.runPurge().block();

        assertThat(mongo.findById(purgeable, Activity.class).block()).isNull();
        assertThat(mongo.findById(legalHold, Activity.class).block()).isNotNull();
    }
}
