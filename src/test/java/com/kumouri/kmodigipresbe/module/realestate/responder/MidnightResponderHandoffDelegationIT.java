package com.kumouri.kmodigipresbe.module.realestate.responder;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeConversation;
import com.kumouri.kmodigipresbe.module.realestate.model.Listing;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3 (Real Estate "Midnight Responder") — off-listing / unknown-intent merge. Proves an inbound buyer SMS
 * the grounded concierge cannot ground (no listing matches the tracked number → {@code NO_LISTING}) is
 * delegated to the E2 responder default-handoff (staff notify + a generic "team member will follow up"
 * reply) instead of dying at the disambiguation prompt — and that without the responder module (no
 * delegate) the byte-identical RE-1 disambiguation reply is the only effect.
 *
 * <p>Drives the signed realestate-mode inbound Twilio SMS over the REUSED webhook ({@code RealEstateQualificationIT}
 * pattern). {@link TwilioSmsService} is a {@code @MockitoBean} capture seam. No live external (§7).
 *
 * <p>The two directions are split into two nested IT classes because the responder module is on/off per
 * {@code @TestPropertySource} (a context property, not per-test).
 */
class MidnightResponderHandoffDelegationIT {

    private static final String AUTH_TOKEN = "twilio_test_midnight_handoff";
    private static final String FORWARDED_HOST = "api-demo.kmosolutionsfoundry.test";
    private static final String TRACKED_NUMBER = "+12145559301";
    private static final String UNKNOWN_NUMBER = "+12145559999"; // no listing tracks this
    private static final String BUYER = "+12145550400";
    private static final String NOTIFY_PHONE = "+12145559500";

    /** Shared seeding + signed-webhook helpers for both directions. */
    abstract static class Base {
        @Autowired WebTestClient web;
        @Autowired ReactiveMongoTemplate mongo;
        @MockitoBean TwilioSmsService twilioSmsService;

        final List<SmsCommunicationRequest> sentSms = new CopyOnWriteArrayList<>();
        UUID tenantId;

        void seedCommon(Set<String> modules) {
            mongo.remove(new Query(), Listing.class).block();
            mongo.remove(new Query(), ConciergeConversation.class).block();
            mongo.remove(new Query(), IntegrationConnection.class).block();
            mongo.remove(new Query(), Tenant.class).block();
            mongo.remove(new Query(), MidnightResponderConfig.class).block();
            sentSms.clear();

            org.mockito.Mockito.when(twilioSmsService.sendSms(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(inv -> {
                        sentSms.add(inv.getArgument(0));
                        return Mono.just(true);
                    });

            tenantId = UUID.randomUUID();
            mongo.save(Tenant.builder().id(tenantId).slug("midnight-handoff-" + tenantId)
                    .displayName("Midnight Handoff IT").status(Tenant.TenantStatus.ACTIVE)
                    .enabledModules(modules)
                    .aiBudgetUsd(new BigDecimal("5.00"))
                    .build()).block();
            // A listing exists for TRACKED_NUMBER, so an inbound to UNKNOWN_NUMBER finds NO listing.
            mongo.save(Listing.builder().id(UUID.randomUUID()).tenantId(tenantId)
                    .addressLine("1 Main St").city("Dallas").state("TX").zip("75201")
                    .trackedPhone(TRACKED_NUMBER).build()).block();
            seedTwilio();
        }

        void seedTwilio() {
            Map<String, String> config = new HashMap<>();
            config.put("smsMode", "realestate");
            config.put("notifyPhone", NOTIFY_PHONE);
            mongo.save(IntegrationConnection.builder()
                    .tenantId(tenantId).provider("twilio")
                    .secrets(new HashMap<>(Map.of("authToken", AUTH_TOKEN, "fromNumber", TRACKED_NUMBER)))
                    .config(config)
                    .build()).block();
        }

        WebTestClient.ResponseSpec postSms(String from, String to, String body) {
            String path = "/public/integrations/twilio/" + tenantId + "/sms";
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("MessageSid", "SM_test_" + UUID.randomUUID());
            form.add("From", from);
            form.add("To", to);
            form.add("Body", body);
            String sig = sign("https://" + FORWARDED_HOST + path, form);
            return web.post().uri(path)
                    .header("X-Forwarded-Proto", "https")
                    .header("X-Forwarded-Host", FORWARDED_HOST)
                    .header("X-Twilio-Signature", sig)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .exchange();
        }

        private static String sign(String fullUrl, MultiValueMap<String, String> form) {
            StringBuilder sb = new StringBuilder(fullUrl);
            TreeMap<String, String> sorted = new TreeMap<>();
            form.forEach((k, v) -> sorted.put(k, v.isEmpty() ? "" : v.get(0)));
            sorted.forEach((k, v) -> sb.append(k).append(v));
            try {
                Mac mac = Mac.getInstance("HmacSHA1");
                mac.init(new SecretKeySpec(AUTH_TOKEN.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
                return Base64.getEncoder().encodeToString(
                        mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (Exception ex) {
                throw new RuntimeException("HMAC-SHA1 failed", ex);
            }
        }
    }

    /** Responder ON (default) → an off-listing inbound delegates to the E2 default-handoff. */
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @AutoConfigureWebTestClient
    @Import(TestcontainersConfiguration.class)
    @TestPropertySource(properties = {
            "kmosf.quartz.proof-job.enabled=false",
            "kmosf.recurring-invoice.spawn-job.enabled=false",
            "kmosf.modules.realestate.enabled=true"
    })
    static class ResponderOn extends Base {

        @BeforeEach
        void seed() {
            seedCommon(Set.of("realestate", "responder", "nurture"));
        }

        @Test
        void offListingInbound_delegatesToResponderHandoff_replyAndStaffNotify() {
            postSms(BUYER, UNKNOWN_NUMBER, "do you have any 4-bedroom listings under 500k?")
                    .expectStatus().isOk();

            // The disambiguation reply (RE-1) AND the responder handoff effects both fire. The default
            // handoff notifies staff (to notifyPhone) and the delegate texts the generic follow-up reply
            // to the buyer.
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                // staff notify to the per-tenant notifyPhone (the E2 default-handoff)
                assertThat(sentSms.stream().anyMatch(s -> NOTIFY_PHONE.equals(s.to().e164())))
                        .as("staff notify SMS to notifyPhone").isTrue();
                // the generic "team member will follow up" reply to the buyer (from the default-handoff)
                assertThat(sentSms.stream().anyMatch(s -> BUYER.equals(s.to().e164())
                        && s.body().toLowerCase().contains("team member")))
                        .as("generic responder handoff reply to the buyer").isTrue();
            });
            // No conversation/listing was created (it was off-listing).
            assertThat(mongo.findAll(ConciergeConversation.class).collectList().block()).isEmpty();
        }
    }

    /** Responder OFF → no delegate wired → byte-identical RE-1 (disambiguation reply only, no notify). */
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @AutoConfigureWebTestClient
    @Import(TestcontainersConfiguration.class)
    @TestPropertySource(properties = {
            "kmosf.quartz.proof-job.enabled=false",
            "kmosf.recurring-invoice.spawn-job.enabled=false",
            "kmosf.modules.realestate.enabled=true",
            "kmosf.modules.responder.enabled=false"
    })
    static class ResponderOff extends Base {

        @BeforeEach
        void seed() {
            seedCommon(Set.of("realestate", "nurture"));
        }

        @Test
        void offListingInbound_noDelegate_disambiguationReplyOnly() {
            postSms(BUYER, UNKNOWN_NUMBER, "do you have any 4-bedroom listings under 500k?")
                    .expectStatus().isOk();

            // Exactly the RE-1 disambiguation reply to the buyer — and NO staff notify (no delegate).
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(sentSms.stream().anyMatch(s -> BUYER.equals(s.to().e164())
                            && s.body().toLowerCase().contains("which property")))
                            .as("RE-1 disambiguation reply").isTrue());
            // Give any (incorrect) async notify a moment, then assert it never happened.
            assertThat(sentSms.stream().noneMatch(s -> NOTIFY_PHONE.equals(s.to().e164())))
                    .as("no staff notify when the responder delegate is absent (byte-identical RE-1)")
                    .isTrue();
            assertThat(mongo.findAll(ConciergeConversation.class).collectList().block()).isEmpty();
        }
    }
}
