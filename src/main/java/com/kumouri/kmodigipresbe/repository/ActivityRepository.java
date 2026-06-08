package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityDirection;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public interface ActivityRepository extends TenantScopedReactiveMongoRepository<Activity, UUID> {

    /**
     * FD-5a — the after-hours health front-desk callbacks for the callback inbox read, newest first.
     * Filters {@code CALL} + {@code INBOUND} activities to those whose {@code body} is the exact FD-3
     * transcript-redaction marker (fence F2). That marker is produced <em>only</em> by the health
     * front-desk voicemail strategy ({@code persistTranscript()=false}); the mole / multi-trade
     * voicemail verticals store the raw transcript as {@code body} instead, so this {@code body}
     * predicate cleanly selects the PHI-free health callbacks and excludes every other inbound-call
     * Activity. Explicit {@code tenantId} predicate (the marker does not auto-scope derived finders).
     */
    Flux<Activity> findAllByTenantIdAndTypeAndDirectionAndBodyOrderByOccurredAtDesc(
            UUID tenantId, ActivityType type, ActivityDirection direction, String body);

    Flux<Activity> findAllByTenantIdAndSubjectTypeAndSubjectIdOrderByOccurredAtDesc(
            UUID tenantId, SubjectType subjectType, UUID subjectId);

    Flux<Activity> findAllByTenantIdAndTypeAndSubjectTypeAndSubjectIdOrderByOccurredAtDesc(
            UUID tenantId, ActivityType type, SubjectType subjectType, UUID subjectId);

    Flux<Activity> findAllByTenantIdAndSubjectTypeAndOccurredAtAfter(
            UUID tenantId, SubjectType subjectType, Instant since);

    Flux<Activity> findAllByTenantIdAndCreatedAtBefore(UUID tenantId, Instant cutoff);

    Mono<Void> deleteByTenantIdAndId(UUID tenantId, UUID id);

    Mono<Activity> findTopByTenantIdAndSubjectTypeAndSubjectIdOrderByOccurredAtDesc(
            UUID tenantId, SubjectType subjectType, UUID subjectId);

    Mono<Boolean> existsByTenantIdAndSubjectTypeAndSubjectIdAndOccurredAtAfter(
            UUID tenantId, SubjectType subjectType, UUID subjectId, Instant since);
}
