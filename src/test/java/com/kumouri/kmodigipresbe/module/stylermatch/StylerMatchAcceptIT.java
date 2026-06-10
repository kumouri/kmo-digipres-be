package com.kumouri.kmodigipresbe.module.stylermatch;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.salonspa.model.AvailabilityWindow;
import com.kumouri.kmodigipresbe.module.salonspa.model.Booking;
import com.kumouri.kmodigipresbe.module.salonspa.model.BookingStatus;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenuItem;
import com.kumouri.kmodigipresbe.module.salonspa.model.StaffMember;
import com.kumouri.kmodigipresbe.module.stylermatch.controller.dto.StylerMatchResponse;
import com.kumouri.kmodigipresbe.module.stylermatch.model.StylerMatch;
import com.kumouri.kmodigipresbe.module.stylermatch.model.StylerMatchStatus;
import com.kumouri.kmodigipresbe.module.stylermatch.service.StylerMatchService;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T12 — StylerMatchAcceptIT: the match → booking funnel. A public match creates a {@link StylerMatch}
 * with a ranked board, then the public accept endpoint books a real salon {@link Booking} via the
 * <strong>unchanged</strong> {@code SalonBookingService.create}, links the match → the booking, stamps
 * which rank was booked, and texts the booking link. Proves the booking is created exactly once
 * (idempotent re-accept), that picking a non-eligible stylist is hard-rejected by the unchanged
 * {@code BookingPolicyService} ({@code 2900}), and the not-found / wrong-token guards. The T9
 * {@code StyleConsultAcceptIT} harness shape ({@code @MockitoBean TwilioSmsService}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.chairfill.enabled=true",
        "kmosf.modules.salon-spa.enabled=true",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.security.widget-token-secret=stylermatch-accept-it-secret-0123456789"
})
class StylerMatchAcceptIT {

    private static final String BOOKING_LINK = "https://shear.example/book";
    private static final Instant SAT_2PM =
            ZonedDateTime.of(2026, 6, 13, 14, 0, 0, 0, ZoneOffset.UTC).toInstant();
    private static final Instant SAT_4PM =
            ZonedDateTime.of(2026, 6, 13, 16, 0, 0, 0, ZoneOffset.UTC).toInstant();

    @Autowired WebTestClient web;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired IntegrationConnectionRepository connections;
    @Autowired PublicWidgetTokenService widgetTokens;

    @MockitoBean TwilioSmsService twilioSmsService;

    private final List<SmsCommunicationRequest> sentSms = new CopyOnWriteArrayList<>();
    private UUID tenantId;
    private UUID mayaId;
    private UUID samId;

    @BeforeEach
    void seed() {
        sentSms.clear();
        Mockito.when(twilioSmsService.sendSms(ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    sentSms.add(inv.getArgument(0));
                    return Mono.just(true);
                });

        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), StylerMatch.class).block();
        mongo.remove(new Query(), Booking.class).block();
        mongo.remove(new Query(), StaffMember.class).block();
        mongo.remove(new Query(), ServiceMenu.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        mongo.save(Tenant.builder()
                .id(tenantId).slug("stylermatch-accept-it-" + tenantId)
                .displayName("StylerMatch Accept IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("salon-spa", "chairfill")).aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();

        TenantContext ctx = new TenantContext(tenantId, null, Set.of("PUBLIC_WIDGET"));
        Map<String, String> config = new HashMap<>();
        config.put("bookingLink", BOOKING_LINK);
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId).provider(TwilioSmsService.PROVIDER)
                .secrets(new HashMap<>(Map.of("authToken", "sandbox", "fromNumber", "+13125550899")))
                .config(config)
                .build()).contextWrite(TenantContextHolder.write(ctx)).block();

        mongo.save(ServiceMenu.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).name("menu")
                .services(List.of(
                        ServiceMenuItem.builder().id("svc-balayage").name("Balayage")
                                .price(new BigDecimal("185")).durationMinutes(180).build(),
                        ServiceMenuItem.builder().id("svc-cut").name("Cut & Style")
                                .price(new BigDecimal("65")).durationMinutes(60).build()))
                .build()).contextWrite(TenantContextHolder.write(ctx)).block();

        mayaId = UUID.randomUUID();
        samId = UUID.randomUUID();
        mongo.save(StaffMember.builder()
                .id(mayaId).tenantId(tenantId).displayName("Maya")
                .specialties(List.of("balayage", "curly hair")).eligibleServiceIds(List.of())
                .availabilityWindows(List.of(saturday())).active(true).build())
                .contextWrite(TenantContextHolder.write(ctx)).block();
        mongo.save(StaffMember.builder()
                .id(samId).tenantId(tenantId).displayName("Sam")
                .specialties(List.of("cut")).eligibleServiceIds(List.of("svc-cut")) // NOT color-certified
                .availabilityWindows(List.of(saturday())).active(true).build())
                .contextWrite(TenantContextHolder.write(ctx)).block();
    }

    private AvailabilityWindow saturday() {
        return AvailabilityWindow.builder().dayOfWeek(DayOfWeek.SATURDAY)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0)).build();
    }

    private String token() {
        return widgetTokens.issue(tenantId, StylerMatchService.WIDGET_TYPE, Duration.ofHours(1));
    }

    private StylerMatchResponse submitMatch(String token) {
        return web.post()
                .uri("/public/integrations/stylermatch/" + token + "/match")
                .bodyValue(Map.of(
                        "serviceMenuItemId", "svc-balayage",
                        "styleCategory", "balayage",
                        "texture", "curly",
                        "slotStart", SAT_2PM.toString(),
                        "slotEnd", SAT_4PM.toString(),
                        "phone", "+13125551234",
                        "name", "Dana Lee"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(StylerMatchResponse.class).returnResult().getResponseBody();
    }

    private WebTestClient.ResponseSpec accept(String token, UUID matchId, String staffMemberIdParam) {
        String uri = "/public/integrations/stylermatch/" + token + "/matches/" + matchId + "/accept"
                + (staffMemberIdParam == null ? "" : "?staffMemberId=" + staffMemberIdParam);
        return web.post().uri(uri).exchange();
    }

    @Test
    void accept_booksTopMatchViaSalonService_linksMatch_stampsRank_textsLink() {
        String token = token();
        StylerMatchResponse match = submitMatch(token);
        assertThat(match).isNotNull();
        assertThat(match.rankedMatches().get(0).getStaffMemberId()).isEqualTo(mayaId); // Maya ranks #1

        StylerMatchResponse accepted = accept(token, match.matchId(), null)
                .expectStatus().isOk()
                .expectBody(StylerMatchResponse.class).returnResult().getResponseBody();

        assertThat(accepted).isNotNull();
        assertThat(accepted.status()).isEqualTo(StylerMatchStatus.BOOKED);
        assertThat(accepted.bookingId()).isNotNull();
        assertThat(accepted.selectedStaffMemberId()).isEqualTo(mayaId);
        assertThat(accepted.selectedRank()).isEqualTo(1);

        // A real salon Booking via the unchanged SalonBookingService — Maya + the requested service + slot.
        List<Booking> bookings = mongo.findAll(Booking.class).collectList().block();
        assertThat(bookings).hasSize(1);
        assertThat(bookings.get(0).getId()).isEqualTo(accepted.bookingId());
        assertThat(bookings.get(0).getStaffMemberId()).isEqualTo(mayaId);
        assertThat(bookings.get(0).getServiceMenuItemId()).isEqualTo("svc-balayage");
        assertThat(bookings.get(0).getStatus()).isEqualTo(BookingStatus.CONFIRMED);

        // The match is BOOKED + linked.
        StylerMatch persisted = mongo.findById(match.matchId(), StylerMatch.class).block();
        assertThat(persisted.getStatus()).isEqualTo(StylerMatchStatus.BOOKED);
        assertThat(persisted.getBookingId()).isEqualTo(bookings.get(0).getId());
        assertThat(persisted.getSelectedRank()).isEqualTo(1);

        // The booking-link SMS fired with the per-tenant link.
        assertThat(sentSms).hasSize(1);
        assertThat(sentSms.get(0).body()).contains(BOOKING_LINK);
    }

    @Test
    void reAccept_isIdempotent_noSecondBooking_noSecondSms() {
        String token = token();
        StylerMatchResponse match = submitMatch(token);

        accept(token, match.matchId(), null).expectStatus().isOk();
        accept(token, match.matchId(), null)
                .expectStatus().isOk()
                .expectBody(StylerMatchResponse.class)
                .value(r -> assertThat(r.status()).isEqualTo(StylerMatchStatus.BOOKED));

        assertThat(mongo.findAll(Booking.class).collectList().block()).hasSize(1);
        assertThat(sentSms).hasSize(1);
    }

    @Test
    void accept_pickingNonEligibleStylist_isHardRejectedByBookingPolicy_2900() {
        String token = token();
        StylerMatchResponse match = submitMatch(token);

        // Sam is visible-but-penalized in the ranking (not color-certified). Explicitly picking Sam for a
        // color service must be hard-rejected by the UNCHANGED BookingPolicyService (2900) — no booking.
        accept(token, match.matchId(), samId.toString())
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.errorCode").isEqualTo(2900);

        assertThat(mongo.findAll(Booking.class).collectList().block()).isEmpty();
        StylerMatch persisted = mongo.findById(match.matchId(), StylerMatch.class).block();
        assertThat(persisted.getStatus()).isEqualTo(StylerMatchStatus.NEW); // unchanged
    }

    @Test
    void accept_unknownMatch_404_4485() {
        accept(token(), UUID.randomUUID(), null)
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4485);
        assertThat(mongo.findAll(Booking.class).collectList().block()).isEmpty();
    }

    @Test
    void accept_wrongWidgetType_401_4480_zeroEffect() {
        String token = token();
        StylerMatchResponse match = submitMatch(token);
        String wrongType = widgetTokens.issue(tenantId, "quote-intake", Duration.ofHours(1));

        accept(wrongType, match.matchId(), null)
                .expectStatus().isEqualTo(401)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4480);
        assertThat(mongo.findAll(Booking.class).collectList().block()).isEmpty();
    }
}
