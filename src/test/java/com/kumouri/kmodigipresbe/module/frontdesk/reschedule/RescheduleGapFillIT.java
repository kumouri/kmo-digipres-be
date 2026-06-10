package com.kumouri.kmodigipresbe.module.frontdesk.reschedule;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistEntry;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistOffer;
import com.kumouri.kmodigipresbe.module.chairfill.automation.RiskTieredPreventionService;
import com.kumouri.kmodigipresbe.module.frontdesk.model.Appointment;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentStatus;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * T7 (Health "RescheduleFlow") — the broad engine-deployment IT. Drives the real T7 beans
 * ({@link RescheduleGapFillSubscriber}, {@link FrontDeskSlotMaterializer}, {@link RescheduleAnalyticsService})
 * + the reused E4 {@link WaitlistClaimEngine} directly (the {@code WaitlistEngineIT} pattern — the engine is
 * consumer-triggered, not an HTTP surface). Frontdesk + waitlist both ON. Twilio → a {@code @MockitoBean}
 * capture seam (its base URL is not config-driven — the {@code WaitlistEngineIT} precedent). No AI / no
 * WireMock here (the gap-fill + claim engine have no AI dependency; the inbound-YES classifier is exercised
 * in {@code RescheduleWaitlistInboundYesIT}). No live external (§7).
 *
 * <h2>Coverage</h2>
 * <ol>
 *   <li>cancel → {@code gapFill} issues ranked offers (reliable patient ranks above flaky), SMS sent;</li>
 *   <li>single YES → claim → the {@link FrontDeskSlotMaterializer} creates a real PHI-free
 *       {@link Appointment} (assert PHI-free — no clinical field set + logistics only);</li>
 *   <li><strong>concurrent double-YES → exactly one claim + one materialize + one new Appointment</strong>
 *       (the showpiece — the E4 atomic guard via the health materializer);</li>
 *   <li>fill-rate analytics (cancellation/offer/claim/filled counts + the rate);</li>
 *   <li>opt-out honored — a {@code sms-opt-out}-tagged contact is not offered;</li>
 *   <li>(defensive) an expired offer can't be claimed → no materialize, no Appointment.</li>
 * </ol>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.frontdesk.enabled=true",
        "kmosf.modules.waitlist.enabled=true",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.waitlist.max-offers=3",
        "kmosf.waitlist.offer-ttl-minutes=10"
})
class RescheduleGapFillIT {

    @Autowired RescheduleGapFillSubscriber subscriber;
    @Autowired WaitlistClaimEngine claimEngine;
    @Autowired RescheduleAnalyticsService analytics;
    @Autowired ReactiveMongoTemplate mongo;

    /** Twilio SMS is mocked — its base URL is not config-driven (the {@code WaitlistEngineIT} precedent). */
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
        mongo.save(Tenant.builder().id(tenantId).slug("reschedule-it-" + tenantId)
                .displayName("RescheduleFlow IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("frontdesk", "waitlist"))
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();
    }

    // ── 1. cancel → gapFill issues ranked offers (reliable above flaky) ──────────

    @Test
    void appointmentCancelled_gapFillIssuesRankedOffers() {
        UUID reliable = seedContact("Rita", "+16185550401", Set.of());
        seedEntry(reliable, 0, 6, Instant.now().minus(Duration.ofDays(20)));   // low risk
        UUID flaky = seedContact("Finn", "+16185550402", Set.of());
        seedEntry(flaky, 2, 1, Instant.now().minus(Duration.ofDays(40)));      // high risk

        UUID freedApptId = UUID.randomUUID();
        Integer sent = subscriber.handle(cancelEvent(freedApptId, Instant.now().plus(Duration.ofHours(3))))
                .block();

        assertThat(sent).isEqualTo(2);
        List<WaitlistOffer> offers = mongo.findAll(WaitlistOffer.class).collectList().block();
        assertThat(offers).hasSize(2);
        // slotKey is the cancelled appointment id; slotType is the health-appt materializer key.
        assertThat(offers).allSatisfy(o -> {
            assertThat(o.getSlotKey()).isEqualTo(freedApptId.toString());
            assertThat(o.getSlotType()).isEqualTo(FrontDeskSlotMaterializer.SLOT_TYPE);
        });
        WaitlistOffer rank0 = offers.stream().filter(o -> o.getRank() == 0).findFirst().orElseThrow();
        assertThat(rank0.getContactId()).isEqualTo(reliable);
        assertThat(sentSms).hasSize(2);
        // The offer copy is generic + PHI-free (the E4 engine template) — no clinical content.
        assertThat(sentSms.get(0).body()).contains("spot just opened");
        assertThat(sentSms.get(0).body().toUpperCase()).contains("YES");

        // Fill-funnel: a CANCELLATION + an OFFER row recorded.
        RescheduleFillStats stats = analytics.stats(tenantId).block();
        assertThat(stats.cancellations()).isEqualTo(1L);
        assertThat(stats.offers()).isEqualTo(1L);
        assertThat(stats.filled()).isZero();
    }

    // ── 2. single YES → claim → materialize a PHI-free Appointment ───────────────

    @Test
    void singleYes_claims_materializesPhiFreeAppointment() {
        UUID rita = seedContact("Rita", "+16185550401", Set.of());
        UUID freedApptId = UUID.randomUUID();
        Instant slotStart = Instant.now().plus(Duration.ofHours(3));
        UUID entryId = seedEntry(rita, 0, 6, Instant.now().minus(Duration.ofDays(20)));
        seedOffer(freedApptId.toString(), rita, "+16185550401", slotStart, entryId,
                Instant.now().plus(Duration.ofMinutes(10)));

        WaitlistClaimEngine.ClaimOutcome outcome = claimEngine.claim(tenantId, "+16185550401").block();
        assertThat(outcome).isEqualTo(WaitlistClaimEngine.ClaimOutcome.WON);

        // A real replacement Appointment was created for the winner — PHI-free (logistics only).
        List<Appointment> appts = mongo.findAll(Appointment.class).collectList().block();
        assertThat(appts).hasSize(1);
        Appointment created = appts.get(0);
        assertThat(created.getContactId()).isEqualTo(rita);
        assertThat(created.getTenantId()).isEqualTo(tenantId);
        assertThat(created.getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
        // The new appointment carries the freed slot's window (compared at ms precision — Mongo truncates
        // an Instant's nanos on the offer round-trip).
        assertThat(created.getScheduledStart())
                .isCloseTo(slotStart, within(1, java.time.temporal.ChronoUnit.MILLIS));
        // PHI-free: the visit type is the closed logistics OTHER bucket, never a procedure/diagnosis.
        assertThat(created.getVisitTypeBucket().name()).isEqualTo("OTHER");

        // Offer CLAIMED, entry FULFILLED, one confirmation SMS, fill-funnel CLAIM + FILLED recorded.
        WaitlistOffer o = mongo.findAll(WaitlistOffer.class).collectList().block().get(0);
        assertThat(o.getStatus()).isEqualTo(WaitlistOffer.Status.CLAIMED);
        WaitlistEntry e = mongo.findById(entryId, WaitlistEntry.class).block();
        assertThat(e.getStatus()).isEqualTo(WaitlistEntry.Status.FULFILLED);
        assertThat(sentSms).hasSize(1);
        RescheduleFillStats stats = analytics.stats(tenantId).block();
        assertThat(stats.filled()).isEqualTo(1L);
    }

    // ── 3. THE SHOWPIECE — concurrent double-YES → exactly one claim/materialize/Appointment ──

    @Test
    void concurrentDoubleYes_exactlyOneClaim_oneMaterialize_oneAppointment() {
        UUID rita = seedContact("Rita", "+16185550401", Set.of());
        UUID finn = seedContact("Finn", "+16185550402", Set.of());
        String slotKey = UUID.randomUUID().toString();
        Instant slotStart = Instant.now().plus(Duration.ofHours(3));
        Instant expires = Instant.now().plus(Duration.ofMinutes(10));
        seedOffer(slotKey, rita, "+16185550401", slotStart, null, expires);
        seedOffer(slotKey, finn, "+16185550402", slotStart, null, expires);

        Mono<WaitlistClaimEngine.ClaimOutcome> a = claimEngine.claim(tenantId, "+16185550401")
                .subscribeOn(reactor.core.scheduler.Schedulers.parallel());
        Mono<WaitlistClaimEngine.ClaimOutcome> b = claimEngine.claim(tenantId, "+16185550402")
                .subscribeOn(reactor.core.scheduler.Schedulers.parallel());

        var outcomes = Mono.zip(a, b).block();
        assertThat(outcomes).isNotNull();
        List<WaitlistClaimEngine.ClaimOutcome> results = List.of(outcomes.getT1(), outcomes.getT2());
        long won = results.stream().filter(o -> o == WaitlistClaimEngine.ClaimOutcome.WON).count();
        long lost = results.stream().filter(o -> o == WaitlistClaimEngine.ClaimOutcome.LOST).count();
        assertThat(won).as("exactly one YES wins the slot").isEqualTo(1L);
        assertThat(lost).as("exactly one YES loses").isEqualTo(1L);

        // Exactly ONE replacement Appointment for the contended slot — no double-materialize / double-book.
        List<Appointment> appts = mongo.findAll(Appointment.class).collectList().block();
        assertThat(appts).as("exactly one new Appointment for the contended freed slot").hasSize(1);

        List<WaitlistOffer> all = mongo.findAll(WaitlistOffer.class).collectList().block();
        assertThat(all.stream().filter(o -> o.getStatus() == WaitlistOffer.Status.CLAIMED).count()).isEqualTo(1L);
        assertThat(all.stream().filter(o -> o.getStatus() == WaitlistOffer.Status.SUPERSEDED).count())
                .isEqualTo(1L);
        // Two SMS: one confirmation (winner) + one apology (loser).
        assertThat(sentSms).hasSize(2);
    }

    // ── 4. opt-out honored ──────────────────────────────────────────────────────

    @Test
    void gapFill_skipsOptedOutContact() {
        UUID optedOut = seedContact("Opt", "+16185550401",
                Set.of(RiskTieredPreventionService.SMS_OPT_OUT_TAG));
        seedEntry(optedOut, 0, 6, Instant.now().minus(Duration.ofDays(20)));
        UUID ok = seedContact("Okay", "+16185550402", Set.of());
        seedEntry(ok, 0, 6, Instant.now().minus(Duration.ofDays(20)));

        Integer sent = subscriber.handle(cancelEvent(UUID.randomUUID(),
                Instant.now().plus(Duration.ofHours(3)))).block();

        assertThat(sent).isEqualTo(1);
        List<WaitlistOffer> offers = mongo.findAll(WaitlistOffer.class).collectList().block();
        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).getContactId()).isEqualTo(ok);
    }

    // ── 5. expired offer can't be claimed → no materialize ──────────────────────

    @Test
    void expiredOffer_cannotBeClaimed_noAppointment() {
        UUID rita = seedContact("Rita", "+16185550401", Set.of());
        seedOffer(UUID.randomUUID().toString(), rita, "+16185550401",
                Instant.now().plus(Duration.ofHours(3)), null, Instant.now().minus(Duration.ofMinutes(1)));

        WaitlistClaimEngine.ClaimOutcome outcome = claimEngine.claim(tenantId, "+16185550401").block();

        assertThat(outcome).isEqualTo(WaitlistClaimEngine.ClaimOutcome.NO_OPEN_OFFER);
        assertThat(mongo.findAll(Appointment.class).collectList().block()).isEmpty();
        assertThat(sentSms).isEmpty();
    }

    // ── 6. a tenant missing the waitlist module → gap-fill is a hard no-op ───────

    @Test
    void cancel_forTenantWithoutWaitlistModule_isNoOp() {
        // Re-tag the tenant: frontdesk only (no waitlist) — the both-module defense-in-depth gate no-ops.
        Tenant t = mongo.findById(tenantId, Tenant.class).block();
        mongo.save(t.toBuilder().enabledModules(Set.of("frontdesk")).build()).block();
        UUID rita = seedContact("Rita", "+16185550401", Set.of());
        seedEntry(rita, 0, 6, Instant.now().minus(Duration.ofDays(20)));

        Integer sent = subscriber.handle(cancelEvent(UUID.randomUUID(),
                Instant.now().plus(Duration.ofHours(3)))).block();

        assertThat(sent).isZero();
        assertThat(mongo.findAll(WaitlistOffer.class).collectList().block()).isEmpty();
        assertThat(sentSms).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private DomainEvent cancelEvent(UUID appointmentId, Instant slotStart) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("appointmentId", appointmentId);
        payload.put("contactId", UUID.randomUUID());
        payload.put("providerId", null);
        payload.put("scheduledStart", slotStart);
        payload.put("scheduledEnd", slotStart.plus(Duration.ofMinutes(30)));
        payload.put("visitTypeBucket", "OTHER");
        return DomainEvent.of(DomainEventType.APPOINTMENT_CANCELLED, tenantId, appointmentId, payload);
    }

    private UUID seedContact(String firstName, String phone, Set<String> tags) {
        return mongo.save(Contact.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .type(ContactType.PERSON)
                .firstName(firstName).displayName(firstName)
                .phones(List.of(PhoneNumber.builder().number(phone).label("mobile").build()))
                .tags(tags)
                .build()).block().getId();
    }

    private UUID seedEntry(UUID contactId, int noShows, int visits, Instant lastVisit) {
        return mongo.save(WaitlistEntry.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .contactId(contactId)
                .slotType(FrontDeskSlotMaterializer.SLOT_TYPE)
                .smsOptIn(true)
                .status(WaitlistEntry.Status.OPEN)
                .priorNoShowCount(noShows).priorVisitCount(visits).lastVisitAt(lastVisit)
                .build()).block().getId();
    }

    private void seedOffer(String slotKey, UUID contactId, String phone, Instant slotStart, UUID entryId,
                           Instant expiresAt) {
        mongo.save(WaitlistOffer.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .slotKey(slotKey).slotType(FrontDeskSlotMaterializer.SLOT_TYPE)
                .waitlistEntryId(entryId)
                .contactId(contactId).contactPhone(phone)
                .slotStart(slotStart).slotEnd(slotStart.plus(Duration.ofMinutes(30))).durationMinutes(30)
                .rank(0)
                .status(WaitlistOffer.Status.OFFERED)
                .sentAt(Instant.now())
                .expiresAt(expiresAt)
                .build()).block();
    }
}
