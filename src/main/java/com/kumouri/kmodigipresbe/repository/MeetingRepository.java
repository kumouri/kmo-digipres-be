package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.meeting.Meeting;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;

import java.util.UUID;

public interface MeetingRepository extends TenantScopedReactiveMongoRepository<Meeting, UUID> {

    /**
     * Finds the Meeting projection keyed by Cal.com booking uid (H.2 / H-D2).
     * Used by {@code CalComWebhookService} to upsert/cancel the projection.
     */
    reactor.core.publisher.Mono<Meeting> findByTenantIdAndCalComBookingUid(UUID tenantId, String calComBookingUid);
}
