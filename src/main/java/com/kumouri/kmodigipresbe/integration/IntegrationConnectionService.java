package com.kumouri.kmodigipresbe.integration;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IntegrationConnectionService {

    private final IntegrationConnectionRepository connections;

    public Flux<IntegrationConnection> findAll() {
        return connections.findAll();
    }

    public Mono<IntegrationConnection> findById(UUID id) {
        return connections.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "IntegrationConnection not found", 2500, 404)));
    }

    public Mono<IntegrationConnection> findByProvider(String provider) {
        return TenantContextHolder.required().flatMap(ctx ->
                connections.findByTenantIdAndProvider(ctx.tenantId(), provider));
    }

    public Mono<IntegrationConnection> requireByProvider(String provider) {
        return findByProvider(provider).switchIfEmpty(Mono.error(() ->
                new DigiPresBeException(
                        "No connection for provider '" + provider + "'", 2501, 412)));
    }

    public Mono<IntegrationConnection> upsert(IntegrationConnection toSave) {
        if (toSave.getConnectedAt() == null) toSave.setConnectedAt(Instant.now());
        return TenantContextHolder.required().flatMap(ctx ->
                connections.findByTenantIdAndProvider(ctx.tenantId(), toSave.getProvider())
                        .flatMap(existing -> {
                            if (toSave.getDisplayName() != null) existing.setDisplayName(toSave.getDisplayName());
                            if (toSave.getStatus() != null) existing.setStatus(toSave.getStatus());
                            if (toSave.getSecrets() != null) existing.setSecrets(toSave.getSecrets());
                            if (toSave.getConfig() != null) existing.setConfig(toSave.getConfig());
                            return connections.save(existing);
                        })
                        .switchIfEmpty(Mono.defer(() -> {
                            toSave.setId(null);
                            return connections.save(toSave);
                        })));
    }

    public Mono<Void> delete(UUID id) {
        return connections.deleteById(id);
    }

    public Mono<IntegrationConnection> markUsed(IntegrationConnection conn) {
        conn.setLastUsedAt(Instant.now());
        return connections.save(conn);
    }
}
