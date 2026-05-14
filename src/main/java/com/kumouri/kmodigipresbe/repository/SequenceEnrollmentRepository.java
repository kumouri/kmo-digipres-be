package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.sequence.SequenceEnrollment;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public interface SequenceEnrollmentRepository
        extends TenantScopedReactiveMongoRepository<SequenceEnrollment, UUID> {

    Mono<SequenceEnrollment> findByTenantIdAndSequenceIdAndContactId(
            UUID tenantId, UUID sequenceId, UUID contactId);

    /**
     * Pulls enrollments due for processing — ACTIVE + ({@code nextFireAt} null or
     * already past). The engine's tick scans this set.
     */
    @org.springframework.data.mongodb.repository.Query(
            "{ 'status': 'ACTIVE', '$or': [ { 'nextFireAt': null }, { 'nextFireAt': { $lte: ?0 } } ] }")
    Flux<SequenceEnrollment> findDueEnrollments(Instant now);
}
