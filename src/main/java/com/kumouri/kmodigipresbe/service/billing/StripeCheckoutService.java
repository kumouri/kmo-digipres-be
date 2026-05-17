package com.kumouri.kmodigipresbe.service.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.stripe.StripeProperties;
import com.kumouri.kmodigipresbe.integration.stripe.StripeWebhookService;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
import com.kumouri.kmodigipresbe.repository.InvoiceRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Generates a Stripe hosted-checkout / payment-link URL for an invoice (Phase E —
 * E-D9). Raw {@link WebClient} against a configurable base URL — NO
 * {@code com.stripe:stripe-java} SDK (the codebase is raw-JSON/WebClient).
 *
 * <h2>No-live-money boundary (§7 hard line)</h2>
 * The Stripe API base URL is {@link StripeProperties#getApiBaseUrl()} — defaulting
 * to {@code https://api.stripe.com} but <strong>pointed at WireMock in every
 * test/CI run</strong>. NO host is hardcoded. The {@code apiKey} comes from the
 * tenant's {@code IntegrationConnection} (sandbox {@code sk_test_}-shaped in
 * tests). The session carries {@code metadata.kmosf_invoice_id} so the existing
 * webhook correlation ({@code StripeWebhookService}) works unchanged. Wiring a
 * live key / initiating a real charge is a separate human action — never the loop.
 *
 * <p>Error codes: {@code 3620} apiKey not configured (412 — distinct from the
 * webhook's {@code 2511} missing-signing-secret); {@code 3621} checkout creation
 * failed (502). Reused: {@code 2300} Invoice not found, {@code 2510} Stripe not
 * connected. Resilience: per-call Resilience4j breaker {@code stripe-checkout}
 * mirroring the QBO breaker, 3-retry, configurable timeout.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeCheckoutService {

    private static final String CB_NAME = "stripe-checkout";

    /** Checkout mode (E-D9). */
    public enum Mode { CHECKOUT_SESSION, PAYMENT_LINK }

    private final InvoiceRepository invoices;
    private final IntegrationConnectionRepository connections;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final StripeProperties properties;
    private final CircuitBreakerRegistry breakers;
    private final DomainEventPublisher events;

    public Mono<CheckoutResult> createCheckoutForInvoice(UUID invoiceId, Mode mode) {
        Mode effectiveMode = mode != null ? mode : Mode.CHECKOUT_SESSION;
        return TenantContextHolder.required().flatMap(ctx ->
                invoices.findById(invoiceId)
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Invoice not found", 2300, 404)))
                        .flatMap(inv -> connections.findByTenantIdAndProvider(
                                        ctx.tenantId(), StripeWebhookService.PROVIDER)
                                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                        "Stripe is not connected for this tenant", 2510, 404)))
                                .flatMap(conn -> {
                                    String apiKey = conn.getSecrets() == null
                                            ? null : conn.getSecrets().get("apiKey");
                                    if (apiKey == null || apiKey.isBlank()) {
                                        return Mono.error(new DigiPresBeException(
                                                "Stripe apiKey is not configured for checkout",
                                                3620, 412));
                                    }
                                    return callStripe(inv, apiKey, effectiveMode)
                                            .flatMap(url -> {
                                                Map<String, Object> payload = new HashMap<>();
                                                payload.put("invoiceId", invoiceId.toString());
                                                payload.put("mode", effectiveMode.name());
                                                events.publish(DomainEvent.of(
                                                        DomainEventType.STRIPE_CHECKOUT_CREATED,
                                                        ctx.tenantId(), invoiceId, payload));
                                                return Mono.just(new CheckoutResult(
                                                        url, effectiveMode.name(), invoiceId));
                                            });
                                })));
    }

    private Mono<String> callStripe(Invoice inv, String apiKey, Mode mode) {
        String path = mode == Mode.PAYMENT_LINK ? "/v1/payment_links" : "/v1/checkout/sessions";
        MultiValueMap<String, String> form = buildForm(inv, mode);

        CircuitBreaker breaker = breakers.circuitBreaker(CB_NAME);
        WebClient client = webClientBuilder.baseUrl(properties.getApiBaseUrl()).build();
        // Fresh Mono.defer per attempt + .cache so retry re-subscription doesn't hit
        // a released response body (the QuickBooksInvoiceSync precedent).
        Mono<String> attempt = Mono.defer(() -> client.post()
                .uri(path)
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                .cache());

        return attempt
                .flatMap(this::extractUrl)
                .transformDeferred(CircuitBreakerOperator.of(breaker))
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(4))
                        .filter(t -> !(t instanceof io.github.resilience4j.circuitbreaker
                                .CallNotPermittedException)
                                && !(t instanceof WebClientResponseException.Unauthorized)
                                && !(t instanceof DigiPresBeException)))
                .onErrorMap(err -> {
                    if (err instanceof DigiPresBeException) return err;
                    return new DigiPresBeException(
                            "Stripe checkout creation failed: " + err.getMessage(), 3621, 502);
                });
    }

    /**
     * Minimal Stripe Checkout-Session / Payment-Link form. Each {@link LineItem}
     * maps to a {@code price_data} line; {@code metadata[kmosf_invoice_id]} is the
     * webhook correlation key (E-D9 / {@code StripeWebhookService}).
     */
    private MultiValueMap<String, String> buildForm(Invoice inv, Mode mode) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        if (mode == Mode.CHECKOUT_SESSION) {
            form.add("mode", "payment");
            form.add("success_url", "https://kmosf.invalid/checkout/success");
            form.add("cancel_url", "https://kmosf.invalid/checkout/cancel");
        }
        String currency = (inv.getCurrency() == null ? "USD" : inv.getCurrency())
                .toLowerCase();
        List<LineItem> lines = inv.getLineItems() == null ? List.of() : inv.getLineItems();
        for (int i = 0; i < lines.size(); i++) {
            LineItem li = lines.get(i);
            BigDecimal unit = li.getUnitPrice() == null ? BigDecimal.ZERO : li.getUnitPrice();
            long unitMinor = unit.multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            BigDecimal qty = li.getQuantity() == null ? BigDecimal.ONE : li.getQuantity();
            String p = "line_items[" + i + "]";
            form.add(p + "[price_data][currency]", currency);
            form.add(p + "[price_data][product_data][name]",
                    li.getDescription() == null
                            ? (li.getSku() == null ? "Invoice item" : li.getSku())
                            : li.getDescription());
            form.add(p + "[price_data][unit_amount]", Long.toString(unitMinor));
            form.add(p + "[quantity]", qty.stripTrailingZeros().toPlainString());
        }
        // The webhook correlation key — StripeWebhookService reads
        // metadata.kmosf_invoice_id verbatim (unchanged).
        form.add("metadata[kmosf_invoice_id]", inv.getId().toString());
        return form;
    }

    private Mono<String> extractUrl(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String url = root.path("url").asText(null);
            if (url == null || url.isBlank()) {
                return Mono.error(new DigiPresBeException(
                        "Stripe checkout response missing url", 3621, 502));
            }
            return Mono.just(url);
        } catch (Exception ex) {
            return Mono.error(new DigiPresBeException(
                    "Stripe checkout response not JSON: " + ex.getMessage(), 3621, 502));
        }
    }

    /** Result of a checkout/payment-link creation. */
    public record CheckoutResult(String url, String mode, UUID invoiceId) {}
}
