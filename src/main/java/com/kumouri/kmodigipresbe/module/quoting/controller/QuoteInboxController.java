package com.kumouri.kmodigipresbe.module.quoting.controller;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.quoting.QuotingAutoConfiguration;
import com.kumouri.kmodigipresbe.module.quoting.controller.dto.QuoteInboxCard;
import com.kumouri.kmodigipresbe.module.quoting.controller.dto.QuoteResponse;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteStatus;
import com.kumouri.kmodigipresbe.module.quoting.repository.QuoteRequestRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * T8 (Home Services "QuoteNow") — the office quote-inbox (Q4): the dispatcher's read surface over the
 * homeowner submissions. Each pre-qualified job shows the read attributes, the price range, the
 * repair-vs-replace recommendation, and the status.
 *
 * <h2>Endpoints (under {@code spring.webflux.base-path=/api/v1})</h2>
 * <ul>
 *   <li>{@code GET /quoting/quotes} (optional {@code ?status=NEW}) → the inbox list
 *       ({@link QuoteInboxCard}, newest first).</li>
 *   <li>{@code GET /quoting/quotes/{id}} → the full {@link QuoteResponse} detail (4435 if absent).</li>
 * </ul>
 *
 * <h2>Gating</h2>
 * Class {@code @ConditionalOnProperty(kmosf.modules.quoting)} (so it is absent from the OpenAPI spec
 * + returns 404 when the module is off — a default-OFF staff route the FE hand-writes types for).
 * Per-tenant membership via {@link TenantModuleRegistry#requireEnabled} (1130/1132). Staff-accessible
 * (the authenticated chain — no extra RoleGuard; the {@code DispatchBoardController} /
 * {@code MissedCallInboxController} precedent).
 */
@RestController
@RequestMapping("/quoting/quotes")
@ConditionalOnProperty(prefix = "kmosf.modules.quoting", name = "enabled")
public class QuoteInboxController {

    private final QuoteRequestRepository quotes;
    private final TenantModuleRegistry modules;

    public QuoteInboxController(QuoteRequestRepository quotes, TenantModuleRegistry modules) {
        this.quotes = quotes;
        this.modules = modules;
    }

    @GetMapping
    public Flux<QuoteInboxCard> list(@RequestParam(value = "status", required = false) QuoteStatus status) {
        return guard()
                .thenMany(TenantContextHolder.required()
                        .flatMapMany(ctx -> status == null
                                ? quotes.findByTenantIdOrderByCreatedAtDesc(ctx.tenantId())
                                : quotes.findByTenantIdAndStatusOrderByCreatedAtDesc(
                                        ctx.tenantId(), status)))
                .map(QuoteInboxCard::from);
    }

    @GetMapping("/{id}")
    public Mono<QuoteResponse> detail(@PathVariable UUID id) {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> quotes.findByTenantIdAndId(ctx.tenantId(), id)
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Quote not found", 4435, 404))))
                .map(QuoteResponse::from);
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(QuotingAutoConfiguration.MODULE_KEY);
    }
}
