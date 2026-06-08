package com.kumouri.kmodigipresbe.module.realestate.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.realestate.model.Listing;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Real Estate Concierge (RE-1) — CRUD for {@link Listing} (the agent console surface).
 *
 * <p>All reads/writes resolve the tenant from the Reactor {@code TenantContext} and stamp it onto the
 * entity, so a listing can never be created or fetched for a foreign tenant ({@code 4253} on a missing /
 * not-owned listing). Hand-constructed as a {@code @Bean} by {@code RealEstateAutoConfiguration} (no
 * {@code @Service} annotation) so it exists only when the module is enabled — blast-radius zero.
 */
@RequiredArgsConstructor
public class ListingService {

    private final ListingRepository listings;

    public Mono<Listing> create(Listing listing) {
        return TenantContextHolder.required().flatMap(ctx -> {
            Listing toSave = listing.toBuilder()
                    .id(UUID.randomUUID())
                    .tenantId(ctx.tenantId())
                    .build();
            return listings.save(toSave);
        });
    }

    public Flux<Listing> list() {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> listings.findByTenantIdOrderByCreatedAtDesc(ctx.tenantId()));
    }

    public Mono<Listing> get(UUID id) {
        return TenantContextHolder.required()
                .flatMap(ctx -> listings.findByIdAndTenantId(id, ctx.tenantId()))
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Listing not found", 4253, 404)));
    }

    /**
     * Patches the mutable fields of a listing. Returns {@code 4253} if the listing does not exist for the
     * current tenant. The id, tenantId, version, and timestamps are preserved (optimistic-lock safe).
     */
    public Mono<Listing> update(UUID id, Listing patch) {
        return get(id).flatMap(existing -> {
            Listing updated = existing.toBuilder()
                    .addressLine(patch.getAddressLine() != null ? patch.getAddressLine() : existing.getAddressLine())
                    .city(patch.getCity() != null ? patch.getCity() : existing.getCity())
                    .state(patch.getState() != null ? patch.getState() : existing.getState())
                    .zip(patch.getZip() != null ? patch.getZip() : existing.getZip())
                    .mlsNumber(patch.getMlsNumber() != null ? patch.getMlsNumber() : existing.getMlsNumber())
                    .price(patch.getPrice() != null ? patch.getPrice() : existing.getPrice())
                    .beds(patch.getBeds() != null ? patch.getBeds() : existing.getBeds())
                    .baths(patch.getBaths() != null ? patch.getBaths() : existing.getBaths())
                    .sqft(patch.getSqft() != null ? patch.getSqft() : existing.getSqft())
                    .status(patch.getStatus() != null ? patch.getStatus() : existing.getStatus())
                    .agentContactId(patch.getAgentContactId() != null
                            ? patch.getAgentContactId() : existing.getAgentContactId())
                    .ownerUserId(patch.getOwnerUserId() != null
                            ? patch.getOwnerUserId() : existing.getOwnerUserId())
                    .trackedPhone(patch.getTrackedPhone() != null
                            ? patch.getTrackedPhone() : existing.getTrackedPhone())
                    .customFields(patch.getCustomFields() != null && !patch.getCustomFields().isEmpty()
                            ? patch.getCustomFields() : existing.getCustomFields())
                    .build();
            return listings.save(updated);
        });
    }
}
