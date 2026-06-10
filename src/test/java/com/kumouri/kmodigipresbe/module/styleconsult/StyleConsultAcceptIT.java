package com.kumouri.kmodigipresbe.module.styleconsult;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.salonspa.model.Booking;
import com.kumouri.kmodigipresbe.module.salonspa.model.BookingStatus;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenuItem;
import com.kumouri.kmodigipresbe.module.styleconsult.controller.dto.StyleConsultResponse;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleConsult;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleConsultStatus;
import com.kumouri.kmodigipresbe.module.styleconsult.service.StyleConsultService;
import com.kumouri.kmodigipresbe.module.styleconsult.support.StyleConsultItStorageTestConfig;
import com.kumouri.kmodigipresbe.module.salonspa.repository.ServiceMenuRepository;
import com.kumouri.kmodigipresbe.repository.ProductRepository;
import com.kumouri.kmodigipresbe.model.catalog.Product;
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
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T9 — StyleConsultAcceptIT: the consult → booking funnel. Drives the full public flow over HTTP — a
 * manual intake creates a {@link StyleConsult} with recommendations, then the public accept endpoint
 * books a real salon {@link Booking} via the unchanged {@code SalonBookingService.create}, links the
 * consult → the booking, and texts the booking link. Proves the booking is created exactly once
 * (idempotent re-accept), the consult is BOOKED + linked, and the booking-link SMS fires.
 *
 * <h2>§7 no-live-external</h2>
 * {@link TwilioSmsService} → a {@code @MockitoBean} capture seam (its base URL is not config-driven —
 * the {@code GapFillWaitlistIT} precedent); no live Cal.com; {@code FileStorageService} = the in-memory
 * {@link StyleConsultItStorageTestConfig} stub. The manual-only intake path makes no AI call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import({TestcontainersConfiguration.class, StyleConsultItStorageTestConfig.class})
@TestPropertySource(properties = {
        "kmosf.modules.chairfill.enabled=true",
        "kmosf.modules.salon-spa.enabled=true",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.security.widget-token-secret=styleconsult-accept-it-secret-0123456789"
})
class StyleConsultAcceptIT {

    private static final String BOOKING_LINK = "https://lumiere.example/book";

    @Autowired WebTestClient web;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired IntegrationConnectionRepository connections;
    @Autowired ServiceMenuRepository menus;
    @Autowired ProductRepository products;
    @Autowired PublicWidgetTokenService widgetTokens;

    @MockitoBean TwilioSmsService twilioSmsService;

    private final List<SmsCommunicationRequest> sentSms = new CopyOnWriteArrayList<>();
    private UUID tenantId;

    @BeforeEach
    void seed() {
        sentSms.clear();
        Mockito.when(twilioSmsService.sendSms(ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    sentSms.add(inv.getArgument(0));
                    return Mono.just(true);
                });

        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), StyleConsult.class).block();
        mongo.remove(new Query(), Booking.class).block();
        mongo.remove(new Query(), ServiceMenu.class).block();
        mongo.remove(new Query(), Product.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        mongo.save(Tenant.builder()
                .id(tenantId).slug("styleconsult-accept-it-" + tenantId)
                .displayName("StyleConsult Accept IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("salon-spa", "chairfill")).aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();

        TenantContext ctx = new TenantContext(tenantId, null, Set.of("PUBLIC_WIDGET"));
        Map<String, String> config = new HashMap<>();
        config.put("bookingLink", BOOKING_LINK);
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId).provider(TwilioSmsService.PROVIDER)
                .secrets(new HashMap<>(Map.of("authToken", "sandbox", "fromNumber", "+13125550799")))
                .config(config)
                .build()).contextWrite(TenantContextHolder.write(ctx)).block();

        menus.save(ServiceMenu.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).name("menu")
                .services(List.of(
                        ServiceMenuItem.builder().id("svc-balayage").name("Balayage")
                                .price(new BigDecimal("185")).durationMinutes(180).build(),
                        ServiceMenuItem.builder().id("svc-cut").name("Cut & Style")
                                .price(new BigDecimal("65")).durationMinutes(60).build()))
                .build()).contextWrite(TenantContextHolder.write(ctx)).block();

        products.save(Product.builder().id(UUID.randomUUID()).tenantId(tenantId).sku("RET-BOND")
                .name("Bond Builder").unitPrice(new BigDecimal("38")).unitCost(new BigDecimal("15"))
                .type(Product.ProductType.GOOD).active(true).build())
                .contextWrite(TenantContextHolder.write(ctx)).block();
    }

    private String token() {
        return widgetTokens.issue(tenantId, StyleConsultService.WIDGET_TYPE, Duration.ofHours(1));
    }

    /** Drive a manual-only intake (no AI) → a persisted consult with recommendations. */
    private StyleConsultResponse submitConsult(String token) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("styleCategory", "balayage");
        builder.part("phone", "+13125551234");
        builder.part("name", "Priya Patel");
        return web.post()
                .uri("/public/integrations/styleconsult/" + token + "/consult")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(StyleConsultResponse.class).returnResult().getResponseBody();
    }

    private WebTestClient.ResponseSpec accept(String token, UUID consultId) {
        return web.post()
                .uri("/public/integrations/styleconsult/" + token + "/consults/" + consultId + "/accept")
                .exchange();
    }

    @Test
    void accept_booksRealBookingViaSalonService_linksConsult_textsBookingLink() {
        String token = token();
        StyleConsultResponse consult = submitConsult(token);
        assertThat(consult).isNotNull();
        assertThat(consult.serviceRecommendations()).isNotEmpty();

        StyleConsultResponse accepted = accept(token, consult.consultId())
                .expectStatus().isOk()
                .expectBody(StyleConsultResponse.class).returnResult().getResponseBody();

        assertThat(accepted).isNotNull();
        assertThat(accepted.status()).isEqualTo(StyleConsultStatus.BOOKED);
        assertThat(accepted.bookingId()).isNotNull();

        // A real salon Booking was created via the unchanged SalonBookingService (CONFIRMED — no deposit/time).
        List<Booking> bookings = mongo.findAll(Booking.class).collectList().block();
        assertThat(bookings).hasSize(1);
        assertThat(bookings.get(0).getId()).isEqualTo(accepted.bookingId());
        assertThat(bookings.get(0).getServiceMenuItemId()).isEqualTo("svc-balayage");
        assertThat(bookings.get(0).getStatus()).isEqualTo(BookingStatus.CONFIRMED);

        // The consult is BOOKED + linked to the booking.
        StyleConsult persisted = mongo.findById(consult.consultId(), StyleConsult.class).block();
        assertThat(persisted.getStatus()).isEqualTo(StyleConsultStatus.BOOKED);
        assertThat(persisted.getBookingId()).isEqualTo(bookings.get(0).getId());

        // The booking-link SMS fired with the per-tenant link.
        assertThat(sentSms).hasSize(1);
        assertThat(sentSms.get(0).body()).contains(BOOKING_LINK);
    }

    @Test
    void reAccept_isIdempotent_noSecondBooking_noSecondSms() {
        String token = token();
        StyleConsultResponse consult = submitConsult(token);

        accept(token, consult.consultId()).expectStatus().isOk();
        // A second accept (no Idempotency-Key needed) → the service-level explicit-boolean guard
        // (bookingId != null) must no-op: re-confirm only, no second Booking/SMS.
        accept(token, consult.consultId())
                .expectStatus().isOk()
                .expectBody(StyleConsultResponse.class)
                .value(r -> assertThat(r.status()).isEqualTo(StyleConsultStatus.BOOKED));

        // Exactly one Booking, exactly one SMS — the re-accept created no second effect.
        assertThat(mongo.findAll(Booking.class).collectList().block()).hasSize(1);
        assertThat(sentSms).hasSize(1);
    }

    @Test
    void accept_explicitServiceChoice_booksThatService() {
        String token = token();
        StyleConsultResponse consult = submitConsult(token);

        web.post()
                .uri("/public/integrations/styleconsult/" + token + "/consults/" + consult.consultId()
                        + "/accept?serviceMenuItemId=svc-cut")
                .exchange()
                .expectStatus().isOk();

        List<Booking> bookings = mongo.findAll(Booking.class).collectList().block();
        assertThat(bookings).hasSize(1);
        assertThat(bookings.get(0).getServiceMenuItemId()).isEqualTo("svc-cut");
    }

    @Test
    void accept_unknownConsult_404_4455() {
        web.post()
                .uri("/public/integrations/styleconsult/" + token() + "/consults/"
                        + UUID.randomUUID() + "/accept")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4455);
        assertThat(mongo.findAll(Booking.class).collectList().block()).isEmpty();
    }

    @Test
    void accept_wrongWidgetType_401_4450_zeroEffect() {
        String token = token();
        StyleConsultResponse consult = submitConsult(token);
        String wrongType = widgetTokens.issue(tenantId, "quote-intake", Duration.ofHours(1));

        web.post()
                .uri("/public/integrations/styleconsult/" + wrongType + "/consults/"
                        + consult.consultId() + "/accept")
                .exchange()
                .expectStatus().isEqualTo(401)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4450);
        assertThat(mongo.findAll(Booking.class).collectList().block()).isEmpty();
    }
}
