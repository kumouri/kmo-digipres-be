package com.kumouri.kmodigipresbe.integration.square;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.integration.SquareWebhookEvent;
import com.kumouri.kmodigipresbe.repository.SquareWebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Security fix BE-14 — the Square webhook is now event-id idempotent. A signed delivery whose
 * {@code event_id} has already been processed is a 200 no-op and does NOT re-run the payment sync.
 * Pure unit (collaborators mocked; no Docker).
 */
class SquareWebhookServiceTest {

    private static final String URL = "https://api.kmosf.test/public/integrations/square/webhook";
    private static final String KEY = "sq_webhook_sig_key";
    private static final String MERCHANT = "MERCHANT_123";
    private static final String EVENT_ID = "evt_abc";

    private SquarePosService posService;
    private SquareWebhookEventRepository webhookEvents;
    private ReactiveMongoTemplate mongo;
    private SquareWebhookService service;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        SquareProperties props = new SquareProperties();
        props.setEnabled(true);
        props.setWebhookUrl(URL);
        props.setWebhookSignatureKey(KEY);

        IntegrationConnectionRepository connections = mock(IntegrationConnectionRepository.class);
        posService = mock(SquarePosService.class);
        webhookEvents = mock(SquareWebhookEventRepository.class);
        mongo = mock(ReactiveMongoTemplate.class);

        IntegrationConnection conn = IntegrationConnection.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).provider("square").build();
        when(mongo.findOne(any(Query.class), eq(IntegrationConnection.class)))
                .thenReturn(Mono.just(conn));
        when(posService.syncSale(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any()))
                .thenReturn(Mono.empty());

        service = new SquareWebhookService(new ObjectMapper(), connections, posService, props, mongo,
                webhookEvents);
    }

    private String body() {
        return "{\"type\":\"payment.completed\",\"merchant_id\":\"" + MERCHANT + "\","
                + "\"event_id\":\"" + EVENT_ID + "\",\"data\":{\"object\":{\"payment\":"
                + "{\"id\":\"pay_1\",\"amount_money\":{\"amount\":500,\"currency\":\"USD\"}}}}}";
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal((URL + body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void firstDelivery_processesAndRecordsLedger() {
        when(webhookEvents.findByTenantIdAndSquareEventId(tenantId, EVENT_ID)).thenReturn(Mono.empty());
        when(webhookEvents.save(any(SquareWebhookEvent.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.handle(sign(body()), body())).verifyComplete();

        verify(webhookEvents).save(any(SquareWebhookEvent.class));
        verify(posService, times(1))
                .syncSale(any(), eq("pay_1"), eq(500L), eq("USD"), any(), any());
    }

    @Test
    void replayedEvent_isDeduped_noSecondSync() {
        // The event was already processed → the ledger probe returns a row → no-op.
        when(webhookEvents.findByTenantIdAndSquareEventId(tenantId, EVENT_ID))
                .thenReturn(Mono.just(SquareWebhookEvent.builder()
                        .id(UUID.randomUUID()).tenantId(tenantId).squareEventId(EVENT_ID).build()));

        StepVerifier.create(service.handle(sign(body()), body())).verifyComplete();

        verify(webhookEvents, never()).save(any());
        verify(posService, never()).syncSale(any(), any(), org.mockito.ArgumentMatchers.anyLong(),
                any(), any(), any());
    }

    @Test
    void invalidSignature_rejected_3010() {
        StepVerifier.create(service.handle("bad-signature", body()))
                .expectErrorSatisfies(e ->
                        org.assertj.core.api.Assertions.assertThat(((DigiPresBeException) e).getErrorCode())
                                .isEqualTo(3010))
                .verify();
        verify(posService, never()).syncSale(any(), any(), org.mockito.ArgumentMatchers.anyLong(),
                any(), any(), any());
    }
}
