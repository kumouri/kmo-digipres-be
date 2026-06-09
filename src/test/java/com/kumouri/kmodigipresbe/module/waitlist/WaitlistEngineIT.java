package com.kumouri.kmodigipresbe.module.waitlist;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistEntry;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistOffer;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistSlot;
import com.kumouri.kmodigipresbe.module.chairfill.automation.RiskTieredPreventionService;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.service.waitlist.GapFillEngine;
import com.kumouri.kmodigipresbe.service.waitlist.SlotMaterializer;
import com.kumouri.kmodigipresbe.service.waitlist.WaitlistClaimEngine;
import com.kumouri.kmodigipresbe.service.waitlist.WaitlistOfferExpiryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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

/**
 * E4 — the broad engine IT for the vertical-agnostic Gap-Fill Waitlist engine. Drives the engine beans
 * directly (the engine is consumer-triggered, not an HTTP/event surface) the way the chairfill
 * {@code GapFillWaitlistIT} drives {@code GapFillService.handle} / {@code WaitlistClaimService}. Twilio →
 * a {@code @MockitoBean} capture seam (its base URL is not config-driven — the {@code GapFillWaitlistIT}
 * precedent). No AI / no WireMock (the engine has no AI dependency). No live external (§7).
 *
 * <h2>Coverage (plan W5)</h2>
 * <ol>
 *   <li>ranking order — a reliable entry ranks ABOVE a flaky one (inverted show-risk);</li>
 *   <li>{@code gapFill} issues top-N offers + SMS (capped at max-offers); the SMS carries the slot;</li>
 *   <li><strong>concurrent double-YES → exactly one CLAIMED + one apology + one materialize</strong> (the
 *       showpiece);</li>
 *   <li>offer expiry — {@code sweepOnce()} flips a past-{@code expiresAt} OFFERED → EXPIRED;</li>
 *   <li><strong>SlotMaterializer invoked on claim</strong> via a {@code @TestConfiguration} materializer;</li>
 *   <li>opt-out skip — a {@code sms-opt-out}-tagged contact is not offered;</li>
 *   <li>(defensive) an expired offer can't be claimed → NO_OPEN_OFFER, no materialize.</li>
 * </ol>
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, WaitlistEngineIT.TestMaterializerConfig.class})
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.waitlist.enabled=true",
        "kmosf.waitlist.max-offers=3",
        "kmosf.waitlist.offer-ttl-minutes=10"
})
class WaitlistEngineIT {

    private static final String SLOT_TYPE = "test-slot";

    @Autowired GapFillEngine gapFillEngine;
    @Autowired WaitlistClaimEngine claimEngine;
    @Autowired WaitlistOfferExpiryService expiryService;
    @Autowired TenantRepository tenants;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired TestMaterializer testMaterializer;

    /** Twilio SMS is mocked — its base URL is not config-driven (the {@code GapFillWaitlistIT} precedent). */
    @MockitoBean
    com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService twilioSmsService;

    private final List<SmsCommunicationRequest> sentSms = new CopyOnWriteArrayList<>();

    private UUID tenantId;

    /**
     * The test {@link SlotMaterializer} — records each {@code materialize} call (proving the engine
     * dispatched to it on claim) and returns a synthetic {@code ("TEST_BOOKING", id)} ref.
     */
    @TestConfiguration
    static class TestMaterializerConfig {
        @Bean
        TestMaterializer testMaterializer() {
            return new TestMaterializer();
        }
    }

    static class TestMaterializer implements SlotMaterializer {
        final List<WaitlistOffer> materialized = new CopyOnWriteArrayList<>();

        @Override
        public String key() {
            return SLOT_TYPE;
        }

        @Override
        public Mono<MaterializedRef> materialize(UUID tenantId, WaitlistOffer claimedOffer, WaitlistSlot slot) {
            materialized.add(claimedOffer);
            return Mono.just(MaterializedRef.of("TEST_BOOKING", UUID.randomUUID()));
        }
    }

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), WaitlistEntry.class).block();
        mongo.remove(new Query(), WaitlistOffer.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        mongo.getCollection("waitlist_slot_claims")
                .flatMap(c -> Mono.from(c.deleteMany(new org.bson.Document()))).block();
        sentSms.clear();
        testMaterializer.materialized.clear();

        org.mockito.Mockito.when(twilioSmsService.sendSms(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    sentSms.add(inv.getArgument(0));
                    return Mono.just(true);
                });

        tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("waitlist"));
    }

    // ── 1. Ranking order — reliable above flaky (inverted show-risk) ──────────

    @Test
    void gapFill_ranksReliableAboveFlaky_andSendsOffers() {
        UUID reliable = seedContact("Dana", "+16185550150", Set.of());
        seedEntry(reliable, 0, 5, Instant.now().minus(Duration.ofDays(10))); // low risk
        UUID flaky = seedContact("Flo", "+16185550151", Set.of());
        seedEntry(flaky, 2, 1, Instant.now().minus(Duration.ofDays(12)));    // high risk

        Instant slotStart = Instant.now().plus(Duration.ofHours(4));
        Integer sent = gapFillEngine.gapFill(tenantId, slot("slot-rank", slotStart)).block();

        assertThat(sent).isEqualTo(2);
        List<WaitlistOffer> all = mongo.findAll(WaitlistOffer.class).collectList().block();
        assertThat(all).hasSize(2);
        WaitlistOffer rank0 = all.stream().filter(o -> o.getRank() == 0).findFirst().orElseThrow();
        WaitlistOffer rank1 = all.stream().filter(o -> o.getRank() == 1).findFirst().orElseThrow();
        assertThat(rank0.getContactId()).isEqualTo(reliable);
        assertThat(rank1.getContactId()).isEqualTo(flaky);
        assertThat(rank0.getStatus()).isEqualTo(WaitlistOffer.Status.OFFERED);
        assertThat(rank0.getSlotType()).isEqualTo(SLOT_TYPE);
        assertThat(sentSms).hasSize(2);
    }

    // ── 2. gapFill issues top-N + SMS carries the slot ────────────────────────

    @Test
    void gapFill_capsAtMaxOffers_andSmsCarriesSlotWindow() {
        for (int i = 0; i < 5; i++) {
            UUID c = seedContact("C" + i, "+161855501" + (60 + i), Set.of());
            seedEntry(c, 0, 0, null);
        }
        Instant slotStart = Instant.now().plus(Duration.ofHours(4));
        Integer sent = gapFillEngine.gapFill(tenantId, slot("slot-cap", slotStart)).block();

        // max-offers=3 -> only 3 of the 5 entries are offered.
        assertThat(sent).isEqualTo(3);
        assertThat(mongo.count(new Query(), WaitlistOffer.class).block()).isEqualTo(3L);
        assertThat(sentSms).hasSize(3);
        assertThat(sentSms.get(0).body().toUpperCase()).contains("YES");
        assertThat(sentSms.get(0).body()).contains("spot just opened");
    }

    // ── 3. THE SHOWPIECE — concurrent double-YES → exactly one CLAIMED + one apology + one materialize ──

    @Test
    void concurrentDoubleYes_exactlyOneClaimed_oneApology_oneMaterialize() {
        UUID dana = seedContact("Dana", "+16185550150", Set.of());
        UUID flo = seedContact("Flo", "+16185550151", Set.of());
        String slotKey = "contended-slot";
        Instant slotStart = Instant.now().plus(Duration.ofHours(4));
        Instant expires = Instant.now().plus(Duration.ofMinutes(10));
        seedOffer(slotKey, dana, "+16185550150", slotStart, expires);
        seedOffer(slotKey, flo, "+16185550151", slotStart, expires);

        Mono<WaitlistClaimEngine.ClaimOutcome> a = claimEngine.claim(tenantId, "+16185550150")
                .subscribeOn(reactor.core.scheduler.Schedulers.parallel());
        Mono<WaitlistClaimEngine.ClaimOutcome> b = claimEngine.claim(tenantId, "+16185550151")
                .subscribeOn(reactor.core.scheduler.Schedulers.parallel());

        var outcomes = Mono.zip(a, b).block();
        assertThat(outcomes).isNotNull();
        List<WaitlistClaimEngine.ClaimOutcome> results = List.of(outcomes.getT1(), outcomes.getT2());

        long won = results.stream().filter(o -> o == WaitlistClaimEngine.ClaimOutcome.WON).count();
        long lost = results.stream().filter(o -> o == WaitlistClaimEngine.ClaimOutcome.LOST).count();
        assertThat(won).as("exactly one YES wins the slot").isEqualTo(1L);
        assertThat(lost).as("exactly one YES loses").isEqualTo(1L);

        // Exactly ONE materialize call — no double-materialize for the contended slot.
        assertThat(testMaterializer.materialized).as("exactly one materialize").hasSize(1);

        // Exactly one offer CLAIMED, exactly one SUPERSEDED.
        List<WaitlistOffer> all = mongo.findAll(WaitlistOffer.class).collectList().block();
        long claimed = all.stream().filter(o -> o.getStatus() == WaitlistOffer.Status.CLAIMED).count();
        long superseded = all.stream().filter(o -> o.getStatus() == WaitlistOffer.Status.SUPERSEDED).count();
        assertThat(claimed).isEqualTo(1L);
        assertThat(superseded).isEqualTo(1L);

        // Two SMS total: one confirmation (winner) + one apology (loser).
        assertThat(sentSms).hasSize(2);
        long apologies = sentSms.stream().filter(s -> s.body() != null
                && s.body().toLowerCase().contains("just taken")).count();
        assertThat(apologies).as("exactly one apologetic auto-reply to the loser").isEqualTo(1L);
    }

    // ── 4. Offer expiry sweep ─────────────────────────────────────────────────

    @Test
    void sweepOnce_flipsStaleOfferedToExpired() {
        UUID dana = seedContact("Dana", "+16185550150", Set.of());
        // expiresAt in the past -> the sweep flips it EXPIRED.
        seedOffer("stale-slot", dana, "+16185550150",
                Instant.now().plus(Duration.ofHours(4)), Instant.now().minus(Duration.ofMinutes(5)));
        // A still-live offer is left OFFERED.
        seedOffer("live-slot", dana, "+16185550150",
                Instant.now().plus(Duration.ofHours(4)), Instant.now().plus(Duration.ofMinutes(10)));

        Long flipped = expiryService.sweepOnce().block();
        assertThat(flipped).isEqualTo(1L);

        List<WaitlistOffer> all = mongo.findAll(WaitlistOffer.class).collectList().block();
        long expired = all.stream().filter(o -> o.getStatus() == WaitlistOffer.Status.EXPIRED).count();
        long offered = all.stream().filter(o -> o.getStatus() == WaitlistOffer.Status.OFFERED).count();
        assertThat(expired).isEqualTo(1L);
        assertThat(offered).isEqualTo(1L);
    }

    // ── 5. SlotMaterializer invoked on a single (non-contended) claim ─────────

    @Test
    void singleYes_invokesMaterializer_recordsRef_claimsOffer() {
        UUID dana = seedContact("Dana", "+16185550150", Set.of());
        String slotKey = "single-slot";
        Instant slotStart = Instant.now().plus(Duration.ofHours(4));
        UUID entryId = seedEntry(dana, 0, 1, Instant.now().minus(Duration.ofDays(5)));
        seedOfferWithEntry(slotKey, dana, "+16185550150", slotStart,
                Instant.now().plus(Duration.ofMinutes(10)), entryId);

        WaitlistClaimEngine.ClaimOutcome outcome = claimEngine.claim(tenantId, "+16185550150").block();
        assertThat(outcome).isEqualTo(WaitlistClaimEngine.ClaimOutcome.WON);

        // The test materializer was invoked with the claimed offer for the right slot.
        assertThat(testMaterializer.materialized).hasSize(1);
        assertThat(testMaterializer.materialized.get(0).getSlotKey()).isEqualTo(slotKey);
        assertThat(testMaterializer.materialized.get(0).getContactId()).isEqualTo(dana);

        // Offer CLAIMED, entry FULFILLED, one confirmation SMS.
        WaitlistOffer o = mongo.findAll(WaitlistOffer.class).collectList().block().get(0);
        assertThat(o.getStatus()).isEqualTo(WaitlistOffer.Status.CLAIMED);
        WaitlistEntry e = mongo.findById(entryId, WaitlistEntry.class).block();
        assertThat(e.getStatus()).isEqualTo(WaitlistEntry.Status.FULFILLED);
        assertThat(sentSms).hasSize(1);
    }

    // ── 6. Opt-out skip ───────────────────────────────────────────────────────

    @Test
    void gapFill_skipsOptedOutContact() {
        UUID optedOut = seedContact("Opt", "+16185550150",
                Set.of(RiskTieredPreventionService.SMS_OPT_OUT_TAG));
        seedEntry(optedOut, 0, 5, Instant.now().minus(Duration.ofDays(5)));
        UUID ok = seedContact("Okay", "+16185550151", Set.of());
        seedEntry(ok, 0, 5, Instant.now().minus(Duration.ofDays(5)));

        Integer sent = gapFillEngine.gapFill(tenantId,
                slot("slot-optout", Instant.now().plus(Duration.ofHours(4)))).block();

        // Only the non-opted-out contact got an offer.
        assertThat(sent).isEqualTo(1);
        List<WaitlistOffer> all = mongo.findAll(WaitlistOffer.class).collectList().block();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getContactId()).isEqualTo(ok);
    }

    // ── 7. Expired offer can't be claimed ─────────────────────────────────────

    @Test
    void expiredOffer_cannotBeClaimed_noMaterialize() {
        UUID dana = seedContact("Dana", "+16185550150", Set.of());
        seedOffer("expired-slot", dana, "+16185550150",
                Instant.now().plus(Duration.ofHours(4)), Instant.now().minus(Duration.ofMinutes(1)));

        WaitlistClaimEngine.ClaimOutcome outcome = claimEngine.claim(tenantId, "+16185550150").block();

        assertThat(outcome).isEqualTo(WaitlistClaimEngine.ClaimOutcome.NO_OPEN_OFFER);
        assertThat(testMaterializer.materialized).isEmpty();
        assertThat(sentSms).isEmpty();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private WaitlistSlot slot(String slotKey, Instant start) {
        return WaitlistSlot.builder()
                .slotType(SLOT_TYPE).slotKey(slotKey).providerId(null)
                .slotStart(start).slotEnd(start.plus(Duration.ofHours(1))).durationMinutes(60)
                .build();
    }

    private void seedTenant(UUID tid, Set<String> modules) {
        tenants.save(Tenant.builder()
                .id(tid).slug("waitlist-e4-" + tid)
                .displayName("Waitlist E4 IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(modules)
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();
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
                .slotType(SLOT_TYPE)
                .smsOptIn(true)
                .status(WaitlistEntry.Status.OPEN)
                .priorNoShowCount(noShows).priorVisitCount(visits).lastVisitAt(lastVisit)
                .build()).block().getId();
    }

    private void seedOffer(String slotKey, UUID contactId, String phone, Instant slotStart, Instant expiresAt) {
        seedOfferWithEntry(slotKey, contactId, phone, slotStart, expiresAt, null);
    }

    private void seedOfferWithEntry(String slotKey, UUID contactId, String phone, Instant slotStart,
                                    Instant expiresAt, UUID entryId) {
        mongo.save(WaitlistOffer.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .slotKey(slotKey).slotType(SLOT_TYPE)
                .waitlistEntryId(entryId)
                .contactId(contactId).contactPhone(phone)
                .slotStart(slotStart).slotEnd(slotStart.plus(Duration.ofHours(1))).durationMinutes(60)
                .rank(0)
                .status(WaitlistOffer.Status.OFFERED)
                .sentAt(Instant.now())
                .expiresAt(expiresAt)
                .build()).block();
    }
}
