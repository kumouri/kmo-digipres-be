package com.kumouri.kmodigipresbe.servicehub;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityDirection;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.contact.Company;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import com.kumouri.kmodigipresbe.model.servicehub.HealthScoreTier;
import com.kumouri.kmodigipresbe.model.servicehub.Ticket;
import com.kumouri.kmodigipresbe.model.servicehub.TicketPriority;
import com.kumouri.kmodigipresbe.model.servicehub.TicketStatus;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.DealRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.TicketRepository;
import com.kumouri.kmodigipresbe.service.servicehub.HealthScoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class HealthScoreIT {

    @Autowired ReactiveMongoTemplate mongo;
    @Autowired TenantRepository tenants;
    @Autowired ContactRepository contacts;
    @Autowired ActivityRepository activities;
    @Autowired TicketRepository tickets;
    @Autowired DealRepository deals;
    @Autowired HealthScoreService healthScoreService;

    private UUID tenantId;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), Company.class).block();
        mongo.remove(new Query(), Activity.class).block();
        mongo.remove(new Query(), Ticket.class).block();
        mongo.remove(new Query(), Deal.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("health-it-" + tenantId)
                .displayName("Health IT").status(Tenant.TenantStatus.ACTIVE)
                .build()).block();
    }

    @Test
    void contactWithNoActivityAndUrgentTicket_scoresRed() {
        Contact contact = contacts.save(Contact.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .displayName("Red Contact").build()).block();

        tickets.save(Ticket.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .contactId(contact.getId())
                .subject("Urgent issue").status(TicketStatus.OPEN)
                .priority(TicketPriority.URGENT).build()).block();

        HealthScoreTier tier = healthScoreService.scoreContact(contact)
                .map(hs -> hs.tier()).block();

        assertThat(tier).isEqualTo(HealthScoreTier.RED);
    }

    @Test
    void contactWithRecentDealWon_scoresGreen() {
        Contact contact = contacts.save(Contact.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .displayName("Green Contact").build()).block();

        // Recent activity within 30 days
        activities.save(Activity.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .type(ActivityType.NOTE).direction(ActivityDirection.INTERNAL)
                .subjectType(SubjectType.CONTACT).subjectId(contact.getId())
                .occurredAt(Instant.now().minus(5, ChronoUnit.DAYS))
                .build()).block();

        // Won deal within 30 days
        deals.save(Deal.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .primaryContactId(contact.getId())
                .title("Big sale").stage(PipelineStage.WON)
                .build()).block();

        HealthScoreTier tier = healthScoreService.scoreContact(contact)
                .map(hs -> hs.tier()).block();

        assertThat(tier).isEqualTo(HealthScoreTier.GREEN);
    }

    @Test
    void computeAll_updatesContactHealthScoreInPlace() {
        Contact contact = contacts.save(Contact.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .displayName("Scored Contact").build()).block();

        Long count = healthScoreService.computeAll().block();
        assertThat(count).isGreaterThanOrEqualTo(1L);

        Contact after = contacts.findByTenantIdAndId(tenantId, contact.getId()).block();
        assertThat(after).isNotNull();
        assertThat(after.getHealthScore()).isNotNull();
        assertThat(after.getHealthScore().score()).isBetween(0, 100);
    }
}
