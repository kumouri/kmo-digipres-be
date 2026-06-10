package com.kumouri.kmodigipresbe.module.frontdesk.reschedule;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistEntry;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistOffer;
import com.kumouri.kmodigipresbe.module.frontdesk.model.Appointment;
import com.kumouri.kmodigipresbe.service.waitlist.WaitlistClaimEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T7 (Health "RescheduleFlow") — the release-blocking PHI-free proof (fence F1). RescheduleFlow's whole pitch
 * is that the front desk recovers the empty chair <strong>without ever touching the chart</strong>. This IT
 * enforces that boundary two ways:
 *
 * <ol>
 *   <li><strong>Static (reflection):</strong> the materialized {@link Appointment} and every T7 waitlist row
 *       ({@link WaitlistEntry}, {@link WaitlistOffer}, {@link RescheduleFillLog}) carry NO clinically-named
 *       field — a guard against future drift (the {@code FrontDeskNoShowScoringServiceIT}
 *       {@code appointmentModelHasNoClinicalField} pattern, "refuse the field, do not flag the field").</li>
 *   <li><strong>Runtime:</strong> after a full cancel → offer → YES → claim → materialize flow, the persisted
 *       {@link Appointment} carries no clinical content (a logistics-only OTHER visit bucket), and the
 *       outbound offer SMS is the generic engine template — it names no procedure / provider / condition.</li>
 * </ol>
 *
 * <p>§7: {@link TwilioSmsService} → {@code @MockitoBean} (captures the SMS body for the no-clinical-content
 * assertion). No transcription anywhere on the path.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.frontdesk.enabled=true",
        "kmosf.modules.waitlist.enabled=true",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class RescheduleFlowPhiFreeIT {

    /** Forbidden clinical tokens — the FD-1 fence-F1 list (mirrors FrontDeskNoShowScoringServiceIT). */
    private static final String[] FORBIDDEN = {
            "diagnos", "procedure", "chiefcomplaint", "complaint", "symptom", "treatment",
            "medication", "prescription", "drug", "dosage", "clinical", "chart", "phi",
            "condition", "icd", "cpt", "labresult", "vital"
    };

    @Autowired RescheduleGapFillSubscriber subscriber;
    @Autowired WaitlistClaimEngine claimEngine;
    @Autowired ReactiveMongoTemplate mongo;

    @MockitoBean TwilioSmsService twilioSmsService;

    private final List<SmsCommunicationRequest> sentSms = new CopyOnWriteArrayList<>();
    private UUID tenantId;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), WaitlistEntry.class).block();
        mongo.remove(new Query(), WaitlistOffer.class).block();
        mongo.remove(new Query(), Appointment.class).block();
        mongo.remove(new Query(), RescheduleFillLog.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        mongo.getCollection("waitlist_slot_claims")
                .flatMap(c -> Mono.from(c.deleteMany(new org.bson.Document()))).block();
        sentSms.clear();
        org.mockito.Mockito.when(twilioSmsService.sendSms(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    sentSms.add(inv.getArgument(0));
                    return Mono.just(true);
                });
        tenantId = UUID.randomUUID();
        mongo.save(Tenant.builder().id(tenantId).slug("reschedule-phi-it-" + tenantId)
                .displayName("RescheduleFlow PHI IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("frontdesk", "waitlist"))
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();
    }

    // ── 1. STATIC: the T7 persisted models carry no clinically-named field ───────

    @Test
    void t7PersistedModelsHaveNoClinicalField() {
        assertNoClinicalField(Appointment.class);
        assertNoClinicalField(WaitlistEntry.class);
        assertNoClinicalField(WaitlistOffer.class);
        assertNoClinicalField(RescheduleFillLog.class);
    }

    // ── 2. RUNTIME: a full flow persists no clinical content + the SMS is generic ──

    @Test
    void fullFlow_persistsNoClinicalContent_offerSmsIsGeneric() {
        UUID rita = seedContact("Rita", "+16185550401");
        UUID entryId = seedEntry(rita);
        UUID freedApptId = UUID.randomUUID();
        Instant slotStart = Instant.now().plus(Duration.ofHours(3));

        // Cancel → gap-fill → offer SMS.
        Integer sent = subscriber.handle(cancelEvent(freedApptId, slotStart)).block();
        assertThat(sent).isEqualTo(1);

        // The offer SMS is the generic engine template — no procedure / provider / condition token.
        assertThat(sentSms).hasSize(1);
        String offerBody = sentSms.get(0).body().toLowerCase(Locale.ROOT);
        for (String bad : FORBIDDEN) {
            assertThat(offerBody)
                    .as("offer SMS must be generic + PHI-free — contained forbidden token '" + bad + "'")
                    .doesNotContain(bad);
        }

        // YES → claim → materialize the replacement Appointment.
        WaitlistClaimEngine.ClaimOutcome outcome = claimEngine.claim(tenantId, "+16185550401").block();
        assertThat(outcome).isEqualTo(WaitlistClaimEngine.ClaimOutcome.WON);

        // The materialized Appointment carries no clinical content (logistics-only OTHER bucket).
        Appointment created = mongo.findAll(Appointment.class).collectList().block().get(0);
        assertThat(created.getVisitTypeBucket().name()).isEqualTo("OTHER");
        // The WaitlistEntry / WaitlistOffer rows also carry no clinical free text (notes is the only free
        // field and we seeded none).
        WaitlistEntry e = mongo.findById(entryId, WaitlistEntry.class).block();
        assertThat(e.getNotes()).isNull();
    }

    // ── helpers ──

    private void assertNoClinicalField(Class<?> type) {
        for (Field f : type.getDeclaredFields()) {
            String name = f.getName().toLowerCase(Locale.ROOT);
            for (String bad : FORBIDDEN) {
                assertThat(name.contains(bad))
                        .as(type.getSimpleName() + " must carry NO clinical field (fence F1) — found '"
                                + f.getName() + "' matching forbidden token '" + bad + "'")
                        .isFalse();
            }
        }
    }

    private com.kumouri.kmodigipresbe.automation.DomainEvent cancelEvent(UUID appointmentId, Instant start) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("appointmentId", appointmentId);
        payload.put("contactId", UUID.randomUUID());
        payload.put("scheduledStart", start);
        payload.put("scheduledEnd", start.plus(Duration.ofMinutes(30)));
        payload.put("visitTypeBucket", "OTHER");
        return com.kumouri.kmodigipresbe.automation.DomainEvent.of(
                com.kumouri.kmodigipresbe.automation.DomainEventType.APPOINTMENT_CANCELLED,
                tenantId, appointmentId, payload);
    }

    private UUID seedContact(String firstName, String phone) {
        return mongo.save(Contact.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .type(ContactType.PERSON)
                .firstName(firstName).displayName(firstName)
                .phones(List.of(PhoneNumber.builder().number(phone).label("mobile").build()))
                .build()).block().getId();
    }

    private UUID seedEntry(UUID contactId) {
        return mongo.save(WaitlistEntry.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .contactId(contactId)
                .slotType(FrontDeskSlotMaterializer.SLOT_TYPE)
                .smsOptIn(true)
                .status(WaitlistEntry.Status.OPEN)
                .priorNoShowCount(0).priorVisitCount(6)
                .lastVisitAt(Instant.now().minus(Duration.ofDays(20)))
                .build()).block().getId();
    }
}
