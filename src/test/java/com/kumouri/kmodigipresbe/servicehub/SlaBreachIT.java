package com.kumouri.kmodigipresbe.servicehub;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.servicehub.SlaPolicy;
import com.kumouri.kmodigipresbe.model.servicehub.Ticket;
import com.kumouri.kmodigipresbe.model.servicehub.TicketPriority;
import com.kumouri.kmodigipresbe.model.servicehub.TicketStatus;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.SlaPolicyRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.TicketRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.servicehub.SlaBreachScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class SlaBreachIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired TicketRepository ticketRepo;
    @Autowired SlaPolicyRepository slaPolicyRepo;
    @Autowired SlaBreachScheduler breachScheduler;

    private UUID tenantId;
    private String staffToken;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        mongo.remove(new Query(), Ticket.class).block();
        mongo.remove(new Query(), SlaPolicy.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("sla-it-" + tenantId)
                .displayName("SLA IT").status(Tenant.TenantStatus.ACTIVE)
                .build()).block();
        users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@sla.test")
                .passwordHash(encoder.encode("hunter2hunter2"))
                .displayName("Staff").roles(Set.of("STAFF"))
                .status(User.UserStatus.ACTIVE).build()).block();

        staffToken = login("staff@sla.test");
    }

    @Test
    void breachSchedulerMarksSlaBreachedAtOnOverdueTicket() {
        // Seed a ticket whose slaResolutionDue is already in the past
        Ticket ticket = Ticket.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .subject("Overdue ticket")
                .status(TicketStatus.OPEN)
                .priority(TicketPriority.URGENT)
                .slaResolutionDue(Instant.now().minus(5, ChronoUnit.MINUTES))
                .build();
        ticketRepo.save(ticket).block();

        // Run the scheduler once directly (don't wait for 60s fixed rate)
        breachScheduler.scanOnce().block();

        // Verify slaBreachedAt is now set
        Ticket after = ticketRepo.findById(ticket.getId()).block();
        assertThat(after).isNotNull();
        assertThat(after.getSlaBreachedAt()).isNotNull();
    }

    @Test
    void breachSchedulerDoesNotMarkResolvedTickets() {
        Ticket ticket = Ticket.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .subject("Already resolved")
                .status(TicketStatus.RESOLVED)
                .priority(TicketPriority.HIGH)
                .slaResolutionDue(Instant.now().minus(2, ChronoUnit.MINUTES))
                .build();
        ticketRepo.save(ticket).block();

        breachScheduler.scanOnce().block();

        Ticket after = ticketRepo.findById(ticket.getId()).block();
        assertThat(after).isNotNull();
        assertThat(after.getSlaBreachedAt()).isNull();
    }

    @Test
    void invalidTransitionReturns409WithCode2900() {
        // Create a ticket via API (starts at NEW)
        Map<?, ?> created = web.post().uri("/tickets")
                .header("Authorization", "Bearer " + staffToken)
                .bodyValue(Map.of("subject", "Transition test", "priority", "LOW"))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        String ticketId = (String) created.get("id");

        // Transition to RESOLVED
        web.post().uri("/tickets/" + ticketId + "/transition?status=RESOLVED")
                .header("Authorization", "Bearer " + staffToken)
                .exchange().expectStatus().isOk();

        // Try illegal RESOLVED -> OPEN
        web.post().uri("/tickets/" + ticketId + "/transition?status=OPEN")
                .header("Authorization", "Bearer " + staffToken)
                .exchange()
                .expectStatus().is4xxClientError()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(2900);
    }

    private String login(String email) {
        Map<?, ?> body = web.post().uri("/auth/login")
                .bodyValue(Map.of("email", email, "password", "hunter2hunter2"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) body.get("token");
    }
}
