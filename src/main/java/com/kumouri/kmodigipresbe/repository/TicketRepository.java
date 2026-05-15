package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.servicehub.Ticket;
import com.kumouri.kmodigipresbe.model.servicehub.TicketStatus;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TicketRepository extends TenantScopedReactiveMongoRepository<Ticket, UUID> {

    Flux<Ticket> findAllByTenantId(UUID tenantId);

    Mono<Ticket> findByTenantIdAndId(UUID tenantId, UUID id);

    /**
     * Finds tickets eligible for SLA breach marking: resolution deadline has passed,
     * not yet marked as breached, and not in a terminal status.
     */
    Flux<Ticket> findAllBySlaResolutionDueBeforeAndSlaBreachedAtIsNullAndStatusNotIn(
            Instant now, List<TicketStatus> terminalStatuses);
}
