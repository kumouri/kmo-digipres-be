package com.kumouri.kmodigipresbe.module.homeservices.integration.quickbooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
import com.kumouri.kmodigipresbe.repository.InvoiceRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Subscribes to {@link DomainEventPublisher#stream()} on {@link PostConstruct}, filters
 * for {@link DomainEventType#INVOICE_FINALIZED}, and pushes the matching CRM
 * {@link Invoice} into Intuit's
 * {@code POST /v3/company/{realmId}/invoice} endpoint when the tenant has a
 * {@link IntegrationConnection} for provider {@code "quickbooks"}.
 *
 * <p><strong>Idempotency:</strong> the QBO invoice id is stored back on the CRM
 * invoice's {@link Invoice#getExternalRefs()} map keyed by {@code "quickbooks"}.
 * If the key is already set, the sync is a no-op — finalizing the same invoice
 * twice will not create two QBO records.
 *
 * <p><strong>Resilience:</strong> wrapped in a Resilience4j circuit breaker keyed
 * {@code "quickbooks-invoice"} (programmatic registry — the codebase doesn't use
 * annotation-driven Resilience4j; this mirrors
 * {@link com.kumouri.kmodigipresbe.automation.webhook.WebhookDeliveryService}).
 * Three retries with exponential backoff before the breaker counts a failure.
 *
 * <p>QBO {@code Customer} resolution is out of scope for Phase 10d — we assume the
 * realm already has a customer that QBO can map by name, or QBO will surface an
 * error which we log and skip. Phase 12 will add a CRM→QBO customer sync.
 *
 * <p>Synthetic tenant context: every outbound effect (loading the connection,
 * saving the updated invoice) runs under a
 * {@code TenantContext(event.tenantId, null, Set.of("INTEGRATION_QUICKBOOKS"))}
 * established with {@link TenantContextHolder#write} — never a ThreadLocal.
 *
 * <p>Error codes: {@code 2800} (no connection), {@code 2801} (token refresh
 * failed), {@code 2802} (invoice push failed).
 */
@Slf4j
@Component
public class QuickBooksInvoiceSync {

    private static final String CB_NAME = "quickbooks-invoice";

    private final DomainEventPublisher events;
    private final IntegrationConnectionRepository connections;
    private final InvoiceRepository invoices;
    private final QuickBooksOAuthService oauth;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final QuickBooksProperties props;
    private final CircuitBreakerRegistry breakers;

    private Disposable subscription;

    public QuickBooksInvoiceSync(DomainEventPublisher events,
                                 IntegrationConnectionRepository connections,
                                 InvoiceRepository invoices,
                                 QuickBooksOAuthService oauth,
                                 WebClient.Builder webClientBuilder,
                                 ObjectMapper objectMapper,
                                 QuickBooksProperties props,
                                 CircuitBreakerRegistry breakers) {
        this.events = events;
        this.connections = connections;
        this.invoices = invoices;
        this.oauth = oauth;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
        this.props = props;
        this.breakers = breakers;
    }

    @PostConstruct
    void subscribe() {
        subscription = events.stream()
                .filter(e -> DomainEventType.INVOICE_FINALIZED.equals(e.type()))
                .flatMap(this::handleSafely, /* concurrency */ 4)
                .subscribe();
        log.info("QuickBooksInvoiceSync subscribed to {} events", DomainEventType.INVOICE_FINALIZED);
    }

    @PreDestroy
    void unsubscribe() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }

    Mono<Void> handleSafely(DomainEvent event) {
        return handle(event)
                .onErrorResume(err -> {
                    log.warn("QBO invoice sync for tenant {} invoice {} failed: {}",
                            event.tenantId(), event.subjectId(), err.toString());
                    return Mono.empty();
                });
    }

    Mono<Void> handle(DomainEvent event) {
        UUID tenantId = event.tenantId();
        UUID invoiceId = event.subjectId();
        if (tenantId == null || invoiceId == null) {
            return Mono.empty();
        }
        TenantContext synthetic = new TenantContext(
                tenantId, null, Set.of("INTEGRATION_QUICKBOOKS"));
        return connections.findByTenantIdAndProvider(tenantId, QuickBooksOAuthService.PROVIDER)
                .flatMap(conn -> invoices.findById(invoiceId)
                        .switchIfEmpty(Mono.error(new DigiPresBeException(
                                "Invoice " + invoiceId + " not found for QBO sync", 2802, 404)))
                        .flatMap(inv -> {
                            if (alreadySynced(inv)) {
                                log.debug("Invoice {} already synced to QBO; skipping", invoiceId);
                                return Mono.<Void>empty();
                            }
                            return oauth.refreshIfNeeded(conn)
                                    .flatMap(refreshed -> pushInvoice(refreshed, inv))
                                    .flatMap(qboInvoiceId -> stamp(inv, qboInvoiceId));
                        }))
                .switchIfEmpty(Mono.fromRunnable(() ->
                        log.debug("Tenant {} has no QBO connection; skipping invoice {}",
                                tenantId, invoiceId)))
                .then()
                .contextWrite(TenantContextHolder.write(synthetic));
    }

    private boolean alreadySynced(Invoice inv) {
        Map<String, String> refs = inv.getExternalRefs();
        return refs != null && refs.get(QuickBooksOAuthService.PROVIDER) != null
                && !refs.get(QuickBooksOAuthService.PROVIDER).isBlank();
    }

    private Mono<String> pushInvoice(IntegrationConnection conn, Invoice inv) {
        String realmId = conn.getConfig() == null ? null : conn.getConfig().get("realmId");
        if (realmId == null || realmId.isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "QBO connection missing realmId", 2800, 412));
        }
        String accessToken = conn.getSecrets() == null ? null : conn.getSecrets().get("accessToken");
        if (accessToken == null || accessToken.isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "QBO connection missing accessToken", 2800, 412));
        }

        Map<String, Object> body = toQboInvoicePayload(inv);
        CircuitBreaker breaker = breakers.circuitBreaker(CB_NAME);
        WebClient client = webClientBuilder.baseUrl(props.getApiBaseUrl()).build();
        return client.post()
                .uri("/v3/company/{realmId}/invoice", realmId)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .transformDeferred(CircuitBreakerOperator.of(breaker))
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(4))
                        .filter(t -> !(t instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException)
                                && !(t instanceof WebClientResponseException.Unauthorized)))
                .flatMap(this::extractQboInvoiceId)
                .onErrorMap(err -> {
                    if (err instanceof DigiPresBeException) return err;
                    return new DigiPresBeException(
                            "QBO invoice push failed: " + err.getMessage(), 2802, 502);
                });
    }

    /**
     * Minimal QBO Invoice payload. Maps each CRM {@link LineItem} to an Intuit
     * {@code SalesItemLineDetail}. Customer is referenced by a placeholder name
     * (Phase 10d does not maintain a CRM Contact → QBO Customer map; tenants
     * will reconcile on the QBO side). Currency is propagated; everything else
     * uses Intuit's defaults.
     */
    Map<String, Object> toQboInvoicePayload(Invoice inv) {
        List<Map<String, Object>> lines = new java.util.ArrayList<>();
        if (inv.getLineItems() != null) {
            for (LineItem li : inv.getLineItems()) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("ItemRef", Map.of("name",
                        li.getDescription() == null ? (li.getSku() == null ? "Service" : li.getSku())
                                : li.getDescription()));
                detail.put("Qty", li.getQuantity() == null ? BigDecimal.ONE : li.getQuantity());
                detail.put("UnitPrice", li.getUnitPrice() == null ? BigDecimal.ZERO : li.getUnitPrice());

                Map<String, Object> line = new LinkedHashMap<>();
                line.put("DetailType", "SalesItemLineDetail");
                line.put("Amount", li.getLineTotal() == null ? BigDecimal.ZERO : li.getLineTotal());
                line.put("Description", li.getDescription());
                line.put("SalesItemLineDetail", detail);
                lines.add(line);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("Line", lines);
        Map<String, Object> customerRef = new LinkedHashMap<>();
        customerRef.put("name", inv.getContactId() == null
                ? "KMOSF CRM Customer"
                : "KMOSF Contact " + inv.getContactId());
        payload.put("CustomerRef", customerRef);
        if (inv.getCurrency() != null) {
            payload.put("CurrencyRef", Map.of("value", inv.getCurrency()));
        }
        if (inv.getDueAt() != null) {
            payload.put("DueDate", inv.getDueAt().toString());
        }
        if (inv.getInvoiceNumber() != null) {
            payload.put("DocNumber", inv.getInvoiceNumber());
        }
        return payload;
    }

    private Mono<String> extractQboInvoiceId(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String id = root.path("Invoice").path("Id").asText(null);
            if (id == null || id.isBlank()) {
                return Mono.error(new DigiPresBeException(
                        "QBO response missing Invoice.Id", 2802, 502));
            }
            return Mono.just(id);
        } catch (Exception ex) {
            return Mono.error(new DigiPresBeException(
                    "QBO response not JSON: " + ex.getMessage(), 2802, 502));
        }
    }

    private Mono<Void> stamp(Invoice inv, String qboInvoiceId) {
        Map<String, String> refs = inv.getExternalRefs() == null
                ? new HashMap<>() : new HashMap<>(inv.getExternalRefs());
        refs.put(QuickBooksOAuthService.PROVIDER, qboInvoiceId);
        inv.setExternalRefs(refs);
        return invoices.save(inv).then();
    }
}
