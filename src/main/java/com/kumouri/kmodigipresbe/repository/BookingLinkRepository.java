package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.calendar.BookingLink;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface BookingLinkRepository
        extends TenantScopedReactiveMongoRepository<BookingLink, UUID> {

    /**
     * Slug is globally unique on this collection. Public booking endpoints look
     * up by slug to discover the tenant — this method is the one safe-to-call-
     * unauthenticated read path; it does NOT filter by tenant because it CAN'T,
     * the tenant is being resolved.
     */
    Mono<BookingLink> findBySlug(String slug);
}
