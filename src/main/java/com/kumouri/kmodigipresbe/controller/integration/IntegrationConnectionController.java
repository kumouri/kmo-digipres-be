package com.kumouri.kmodigipresbe.controller.integration;

import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Per-tenant integration-connection management.
 *
 * <p><strong>Security fix BE-03:</strong> read endpoints return a {@link ConnectionView}
 * that NEVER serializes the {@code secrets} or {@code config} maps (Stripe API keys,
 * Twilio auth tokens, OAuth access/refresh tokens) — only non-sensitive metadata plus a
 * boolean {@code hasSecrets}. Write endpoints bind a {@link ConnectionWrite} DTO that
 * cannot set {@code tenantId}/{@code id}/{@code version} (mass-assignment fix). ADMIN
 * gating is enforced centrally by
 * {@link com.kumouri.kmodigipresbe.tenancy.StaffAuthorizationWebFilter} (these routes are
 * under {@code /integrations/connections/**}).
 */
@RestController
@RequestMapping("/integrations/connections")
@RequiredArgsConstructor
public class IntegrationConnectionController {

    private final IntegrationConnectionService service;

    @GetMapping
    public Flux<ConnectionView> list() {
        return service.findAll().map(ConnectionView::of);
    }

    @GetMapping("/{id}")
    public Mono<ConnectionView> get(@PathVariable UUID id) {
        return service.findById(id).map(ConnectionView::of);
    }

    @GetMapping("/by-provider/{provider}")
    public Mono<ConnectionView> getByProvider(@PathVariable String provider) {
        return service.findByProvider(provider).map(ConnectionView::of);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ConnectionView> upsert(@RequestBody ConnectionWrite body) {
        return service.upsert(body.toEntity()).map(ConnectionView::of);
    }

    @PutMapping("/{id}")
    public Mono<ConnectionView> update(@PathVariable UUID id,
                                       @RequestBody ConnectionWrite body) {
        return service.upsert(body.toEntity()).map(ConnectionView::of);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return service.delete(id);
    }

    /**
     * Redacted read projection (BE-03). Exposes only non-sensitive metadata — the raw
     * {@code secrets}/{@code config} maps are intentionally absent. {@code hasSecrets}
     * lets the UI show "credentials configured" without revealing them.
     */
    public record ConnectionView(String id, String provider, String displayName,
                                 String status, Instant connectedAt, Instant lastUsedAt,
                                 String lastErrorMessage, boolean hasSecrets) {
        public static ConnectionView of(IntegrationConnection c) {
            return new ConnectionView(
                    c.getId() == null ? null : c.getId().toString(),
                    c.getProvider(),
                    c.getDisplayName(),
                    c.getStatus() == null ? null : c.getStatus().name(),
                    c.getConnectedAt(),
                    c.getLastUsedAt(),
                    c.getLastErrorMessage(),
                    c.getSecrets() != null && !c.getSecrets().isEmpty());
        }
    }

    /**
     * Write DTO (BE-03 mass-assignment fix). Carries only the fields a tenant admin may
     * set — {@code tenantId} (stamped from the JWT by {@code TenantStampingCallback}),
     * {@code id}, and {@code version} are deliberately NOT bindable. {@code provider}
     * keys the upsert (one connection per tenant+provider).
     *
     * <p>{@code secrets}/{@code config} use null-means-leave-unchanged semantics on an
     * update: the service's {@code upsert} only overwrites them when non-null, so a PUT
     * that omits {@code secrets} preserves the stored credentials rather than wiping
     * them. On a fresh insert the entity's {@code @Builder.Default} empty map applies.
     */
    public record ConnectionWrite(@NotBlank String provider, String displayName,
                                  IntegrationConnection.Status status,
                                  Map<String, String> secrets,
                                  Map<String, String> config) {
        public IntegrationConnection toEntity() {
            // Build with the no-args constructor + setters (NOT the builder) so that an
            // omitted secrets/config stays null — the @Builder.Default empty map would
            // otherwise make "null means leave unchanged" impossible and a metadata-only
            // PUT would wipe stored credentials. tenantId/id/version are never set here.
            IntegrationConnection c = new IntegrationConnection();
            c.setProvider(provider);
            if (displayName != null) c.setDisplayName(displayName);
            // status defaults to ACTIVE (the entity's documented default) so a fresh
            // insert is ACTIVE; on update the service's upsert overwrites status when
            // present, which is the admin's intent for this surface.
            c.setStatus(status == null ? IntegrationConnection.Status.ACTIVE : status);
            if (secrets != null) c.setSecrets(secrets);
            if (config != null) c.setConfig(config);
            return c;
        }
    }
}
