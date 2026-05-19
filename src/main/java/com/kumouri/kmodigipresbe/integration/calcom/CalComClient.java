package com.kumouri.kmodigipresbe.integration.calcom;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * HTTP client for the Cal.com REST API (Phase H — H.2 / H-D7).
 *
 * <p>Uses the <em>shared</em> {@link WebClient.Builder} Spring bean (the
 * {@code StripeCheckoutService} / {@code DocumensoClient} precedent) — no new
 * pooled/resource-owning bean is introduced. {@link CalComProperties#getApiBaseUrl()}
 * defaults to a non-routable {@code .invalid} host; in tests it is overridden to
 * the WireMock base URL via {@code @DynamicPropertySource} (§7 hard boundary).
 *
 * <h2>H-D7 lifecycle note</h2>
 * No new SDK, HTTP connection pool, Netty event-loop, or scheduler/thread-pool bean
 * is introduced. The {@code WebClient} is built per-call from the shared
 * {@code WebClient.Builder} exactly as {@code DocumensoClient} does. This is the
 * mandatory explicit lifecycle statement (H-D7).
 *
 * <p>No live Cal.com anywhere — the default base URL is non-routable; tests always
 * override to a sandbox fake (§7).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalComClient {

    // H-D7: shared builder — no new pool/SDK/Netty bean introduced.
    private final WebClient.Builder webClientBuilder;
    private final CalComProperties properties;

    /**
     * Fetches a booking from the Cal.com API by uid.
     *
     * <p>This is a placeholder for future outbound Cal.com reads (e.g. re-sync).
     * Phase H inbound reconciliation is driven entirely by the verified webhook
     * payload — no outbound call is required for the core H.2 flow.
     *
     * @param tenantApiKey the tenant's Cal.com API key (from their IntegrationConnection)
     * @param bookingUid   the Cal.com booking uid
     * @return the raw JSON body string; empty if not found
     */
    public Mono<String> fetchBooking(String tenantApiKey, String bookingUid) {
        WebClient client = webClientBuilder
                .baseUrl(properties.getApiBaseUrl())
                .build();

        return client.get()
                .uri("/v1/bookings/{uid}", bookingUid)
                .header("Authorization", "Bearer " + tenantApiKey)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(ex -> {
                    log.warn("CalComClient.fetchBooking failed for uid {}: {}", bookingUid, ex.getMessage());
                    return Mono.empty();
                });
    }
}
