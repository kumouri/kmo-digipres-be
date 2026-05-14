package com.kumouri.kmodigipresbe.service.inbox;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.ActivityCrudService;
import com.kumouri.kmodigipresbe.service.communication.TransactionalEmailService;
import com.kumouri.kmodigipresbe.service.communication.TransactionalSendRequest;
import com.kumouri.kmodigipresbe.service.communication.TransactionalSendResult;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Saving an Activity with an {@code @alice} mention in the body fires
 * MENTION_CREATED → MentionNotificationListener → TransactionalEmailService.send.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MentionFanOutIT {

    @Autowired ActivityCrudService activitiesService;
    @Autowired UserRepository users;
    @Autowired TenantRepository tenants;
    @Autowired PasswordEncoder encoder;
    @Autowired ReactiveMongoTemplate mongo;

    @MockitoBean TransactionalEmailService transactionalEmail;

    private final AtomicReference<String> lastTo = new AtomicReference<>();
    private final AtomicReference<String> lastSubject = new AtomicReference<>();

    @BeforeEach
    void setup() {
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        mongo.remove(new Query(), Activity.class).block();
        lastTo.set(null);
        lastSubject.set(null);

        when(transactionalEmail.send(any(TransactionalSendRequest.class)))
                .thenAnswer(inv -> {
                    TransactionalSendRequest req = inv.getArgument(0);
                    lastTo.set(req.to() == null || req.to().isEmpty() ? null : req.to().get(0));
                    lastSubject.set(req.subject());
                    return Mono.just(new TransactionalSendResult(
                            "mid-" + UUID.randomUUID(),
                            req.to() == null ? null : req.to().get(0),
                            Instant.now()));
                });
    }

    @Test
    void mentionInActivityNotes_sendsNotificationEmailToMentionedUser() {
        UUID tenantId = UUID.randomUUID();
        TenantContext ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));

        tenants.save(Tenant.builder()
                        .id(tenantId).slug("mention-" + tenantId).displayName("Mention IT")
                        .status(Tenant.TenantStatus.ACTIVE)
                        .build())
                .block();
        users.save(User.builder()
                        .id(UUID.randomUUID()).tenantId(tenantId)
                        .email("alice@example.test")
                        .passwordHash(encoder.encode("hunter2hunter2"))
                        .displayName("Alice")
                        .roles(Set.of("STAFF"))
                        .status(User.UserStatus.ACTIVE)
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        Activity activity = Activity.builder()
                .type(ActivityType.NOTE)
                .summary("Follow up needed")
                .body("Please loop in @alice to confirm the renewal.")
                .build();

        activitiesService.create(activity)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        // MENTION_CREATED is published asynchronously; wait for the listener to fire.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(lastTo.get()).isEqualTo("alice@example.test"));
        assertThat(lastSubject.get()).contains("mentioned");
    }
}
