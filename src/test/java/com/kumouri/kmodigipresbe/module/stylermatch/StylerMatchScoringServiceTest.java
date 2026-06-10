package com.kumouri.kmodigipresbe.module.stylermatch;

import com.kumouri.kmodigipresbe.module.salonspa.model.AvailabilityWindow;
import com.kumouri.kmodigipresbe.module.salonspa.model.Booking;
import com.kumouri.kmodigipresbe.module.salonspa.model.BookingStatus;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenuItem;
import com.kumouri.kmodigipresbe.module.salonspa.model.StaffMember;
import com.kumouri.kmodigipresbe.module.stylermatch.model.MatchRequest;
import com.kumouri.kmodigipresbe.module.stylermatch.model.RankedMatch;
import com.kumouri.kmodigipresbe.module.stylermatch.service.StylerMatchScoringService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T12 — {@link StylerMatchScoringService} pure-engine unit test (no Docker, fast). The marquee proof is
 * the <strong>match ranking</strong>: a stylist whose free-text {@code specialties} + availability fit
 * the requested style/slot ranks first, an availability conflict demotes a stylist, an eligibility miss
 * is heavily penalized (but still visible), a returning client's preferred stylist is bumped, the
 * rationale is present and always carries the {@link StylerMatchScoringService#STYLIST_CONFIRM_NOTE}
 * guardrail, an empty-specialties stylist is ranked-not-excluded, and ties are deterministic across
 * runs. No Spring context, no Mongo — the engine is pure over the lists the orchestrator passes in.
 */
class StylerMatchScoringServiceTest {

    private final StylerMatchScoringService scorer = new StylerMatchScoringService("UTC");

    // A Saturday afternoon (2026-06-13 is a Saturday) — used for the slot signal.
    private static final Instant SAT_2PM =
            ZonedDateTime.of(2026, 6, 13, 14, 0, 0, 0, ZoneOffset.UTC).toInstant();
    private static final Instant SAT_4PM =
            ZonedDateTime.of(2026, 6, 13, 16, 0, 0, 0, ZoneOffset.UTC).toInstant();

    private StaffMember stylist(String name, List<String> specialties, List<String> eligible,
                                List<AvailabilityWindow> windows) {
        return StaffMember.builder()
                .id(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .displayName(name)
                .specialties(specialties)
                .eligibleServiceIds(eligible)
                .availabilityWindows(windows)
                .active(true)
                .build();
    }

    private AvailabilityWindow saturday(int fromHour, int toHour) {
        return AvailabilityWindow.builder()
                .dayOfWeek(DayOfWeek.SATURDAY)
                .startTime(LocalTime.of(fromHour, 0))
                .endTime(LocalTime.of(toHour, 0))
                .build();
    }

    private ServiceMenu menu(ServiceMenuItem... items) {
        return ServiceMenu.builder().id(UUID.randomUUID()).tenantId(UUID.randomUUID())
                .name("menu").services(List.of(items)).build();
    }

    private ServiceMenuItem item(String id, String name, String price) {
        return ServiceMenuItem.builder().id(id).name(name).price(new BigDecimal(price))
                .durationMinutes(120).build();
    }

    @Test
    void specialtyFit_ranksTheRightStylistFirst() {
        StaffMember maya = stylist("Maya", List.of("balayage", "curly hair", "color correction"),
                List.of(), List.of());
        StaffMember sam = stylist("Sam", List.of("cut", "keratin"), List.of(), List.of());

        MatchRequest req = MatchRequest.builder()
                .serviceMenuItemId("svc-balayage").styleCategory("balayage").texture("curly")
                .build();

        List<RankedMatch> ranked = scorer.rank(req, List.of(sam, maya),
                List.of(menu(item("svc-balayage", "Balayage", "185"))), List.of());

        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0).getDisplayName()).isEqualTo("Maya");
        assertThat(ranked.get(0).getSpecialtyFit()).isGreaterThan(ranked.get(1).getSpecialtyFit());
        assertThat(ranked.get(0).getScore()).isGreaterThan(ranked.get(1).getScore());
    }

    @Test
    void everyRationale_carriesTheStylistConfirmGuardrail() {
        StaffMember maya = stylist("Maya", List.of("balayage"), List.of(), List.of());
        StaffMember sam = stylist("Sam", List.of(), List.of(), List.of());

        List<RankedMatch> ranked = scorer.rank(
                MatchRequest.builder().styleCategory("balayage").build(),
                List.of(maya, sam), List.of(), List.of());

        assertThat(ranked).isNotEmpty();
        assertThat(ranked).allSatisfy(r -> {
            assertThat(r.getRationale()).isNotBlank();
            assertThat(r.getRationale()).contains(StylerMatchScoringService.STYLIST_CONFIRM_NOTE);
        });
    }

    @Test
    void emptySpecialtiesStylist_isRankedNotExcluded() {
        StaffMember versatile = stylist("Versatile", List.of(), List.of(), List.of());

        List<RankedMatch> ranked = scorer.rank(
                MatchRequest.builder().styleCategory("balayage").build(),
                List.of(versatile), List.of(), List.of());

        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).getDisplayName()).isEqualTo("Versatile");
        assertThat(ranked.get(0).getScore()).isGreaterThan(0.0);
    }

    @Test
    void availabilityConflict_demotesStylist() {
        StaffMember busy = stylist("Busy", List.of("balayage"), List.of(), List.of(saturday(9, 18)));
        StaffMember free = stylist("Free", List.of("balayage"), List.of(), List.of(saturday(9, 18)));

        // Busy already has a CONFIRMED booking overlapping the requested 2-4pm slot.
        Booking conflict = Booking.builder()
                .id(UUID.randomUUID()).tenantId(busy.getTenantId())
                .staffMemberId(busy.getId()).status(BookingStatus.CONFIRMED)
                .scheduledStart(SAT_2PM).scheduledEnd(SAT_4PM).build();

        MatchRequest req = MatchRequest.builder()
                .styleCategory("balayage").slotStart(SAT_2PM).slotEnd(SAT_4PM).build();

        List<RankedMatch> ranked = scorer.rank(req, List.of(busy, free), List.of(), List.of(conflict));

        assertThat(ranked.get(0).getDisplayName()).isEqualTo("Free");
        RankedMatch busyRanked = ranked.stream()
                .filter(r -> r.getDisplayName().equals("Busy")).findFirst().orElseThrow();
        assertThat(busyRanked.getAvailability()).isEqualTo(0.0);
        assertThat(ranked.get(0).getAvailability()).isEqualTo(1.0); // Free covers the slot + no conflict
    }

    @Test
    void availabilityWindowCoverage_outranksOutOfWindow() {
        StaffMember inWindow = stylist("InWindow", List.of(), List.of(), List.of(saturday(9, 18)));
        StaffMember noWindow = stylist("NoWindow", List.of(), List.of(), List.of());

        MatchRequest req = MatchRequest.builder().slotStart(SAT_2PM).slotEnd(SAT_4PM).build();

        List<RankedMatch> ranked = scorer.rank(req, List.of(noWindow, inWindow), List.of(), List.of());

        assertThat(ranked.get(0).getDisplayName()).isEqualTo("InWindow");
        assertThat(ranked.get(0).getAvailability()).isEqualTo(1.0);
    }

    @Test
    void eligibilityMiss_isHeavilyPenalizedButVisible() {
        // Sam is NOT certified for color services (eligible only for svc-cut) but has the balayage word.
        StaffMember sam = stylist("Sam", List.of("balayage"), List.of("svc-cut"), List.of());
        StaffMember maya = stylist("Maya", List.of("balayage"), List.of(), List.of());

        MatchRequest req = MatchRequest.builder()
                .serviceMenuItemId("svc-balayage").styleCategory("balayage").build();

        List<RankedMatch> ranked = scorer.rank(req, List.of(sam, maya),
                List.of(menu(item("svc-balayage", "Balayage", "185"), item("svc-cut", "Cut", "65"))),
                List.of());

        // Both visible (no hard exclusion in the ranker), but the eligible Maya outranks the penalized Sam.
        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0).getDisplayName()).isEqualTo("Maya");
        RankedMatch samRanked = ranked.stream()
                .filter(r -> r.getDisplayName().equals("Sam")).findFirst().orElseThrow();
        assertThat(samRanked.isEligibleForRequestedService()).isFalse();
        assertThat(ranked.get(0).isEligibleForRequestedService()).isTrue();
        assertThat(samRanked.getScore()).isLessThan(ranked.get(0).getScore());
        assertThat(samRanked.getRationale()).contains("not certified");
    }

    @Test
    void preference_bumpsPreviouslySeenStylist() {
        StaffMember maya = stylist("Maya", List.of("balayage"), List.of(), List.of());
        StaffMember jordan = stylist("Jordan", List.of("balayage"), List.of(), List.of());
        UUID contactId = UUID.randomUUID();

        // The contact has 3 prior COMPLETED bookings with Jordan, none with Maya.
        List<Booking> history = List.of(
                completed(contactId, jordan.getId()),
                completed(contactId, jordan.getId()),
                completed(contactId, jordan.getId()));

        MatchRequest req = MatchRequest.builder()
                .styleCategory("balayage").contactId(contactId).build();

        List<RankedMatch> ranked = scorer.rank(req, List.of(maya, jordan), List.of(), history);

        // Same specialty fit, but Jordan's loyalty preference bumps them to #1.
        assertThat(ranked.get(0).getDisplayName()).isEqualTo("Jordan");
        assertThat(ranked.get(0).getPreference()).isGreaterThan(0.0);
        assertThat(ranked.get(0).getRationale()).contains("3 times before");
    }

    @Test
    void explicitPreferredStylist_isTheLargestBump_andSurfacedInRationale() {
        StaffMember maya = stylist("Maya", List.of("balayage"), List.of(), List.of());
        StaffMember jordan = stylist("Jordan", List.of(), List.of(), List.of());

        MatchRequest req = MatchRequest.builder()
                .preferredStaffMemberId(jordan.getId()).build();

        List<RankedMatch> ranked = scorer.rank(req, List.of(maya, jordan), List.of(), List.of());

        RankedMatch jordanRanked = ranked.stream()
                .filter(r -> r.getDisplayName().equals("Jordan")).findFirst().orElseThrow();
        assertThat(jordanRanked.getPreference()).isEqualTo(1.0);
        assertThat(jordanRanked.getRationale()).contains("Your requested stylist");
    }

    @Test
    void ranking_isDeterministicAcrossRuns() {
        StaffMember a = stylist("Alex", List.of("balayage"), List.of(), List.of());
        StaffMember b = stylist("Bailey", List.of("balayage"), List.of(), List.of());
        StaffMember c = stylist("Casey", List.of("balayage"), List.of(), List.of());
        MatchRequest req = MatchRequest.builder().styleCategory("balayage").build();

        List<RankedMatch> run1 = scorer.rank(req, List.of(c, a, b), List.of(), List.of());
        List<RankedMatch> run2 = scorer.rank(req, List.of(b, c, a), List.of(), List.of());

        // Equal scores → deterministic displayName tiebreak → identical order regardless of input order.
        assertThat(run1.stream().map(RankedMatch::getDisplayName).toList())
                .containsExactly("Alex", "Bailey", "Casey");
        assertThat(run2.stream().map(RankedMatch::getDisplayName).toList())
                .containsExactly("Alex", "Bailey", "Casey");
    }

    @Test
    void emptyStylistList_returnsEmpty() {
        assertThat(scorer.rank(MatchRequest.builder().styleCategory("balayage").build(),
                List.of(), List.of(), List.of())).isEmpty();
    }

    @Test
    void confidence_reflectsSignalsPresent() {
        StaffMember maya = stylist("Maya", List.of("balayage"), List.of(), List.of());

        // Style + slot + contact present → all three signals → confidence 1.0.
        RankedMatch full = scorer.rank(MatchRequest.builder()
                        .styleCategory("balayage").slotStart(SAT_2PM).contactId(UUID.randomUUID()).build(),
                List.of(maya), List.of(), List.of()).get(0);
        assertThat(full.getConfidence()).isEqualTo(1.0);

        // Style only → 1 of 3.
        RankedMatch styleOnly = scorer.rank(MatchRequest.builder().styleCategory("balayage").build(),
                List.of(maya), List.of(), List.of()).get(0);
        assertThat(styleOnly.getConfidence()).isCloseTo(1.0 / 3.0, org.assertj.core.data.Offset.offset(0.01));
    }

    private Booking completed(UUID contactId, UUID stylistId) {
        return Booking.builder()
                .id(UUID.randomUUID()).tenantId(UUID.randomUUID())
                .contactId(contactId).staffMemberId(stylistId)
                .status(BookingStatus.COMPLETED)
                .scheduledStart(Instant.now().minusSeconds(86400 * 30))
                .scheduledEnd(Instant.now().minusSeconds(86400 * 30 - 3600))
                .build();
    }
}
