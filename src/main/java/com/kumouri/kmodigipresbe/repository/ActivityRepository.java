package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface ActivityRepository extends TenantScopedReactiveMongoRepository<Activity, UUID> {
    Flux<Activity> findAllByTenantIdAndSubjectTypeAndSubjectIdOrderByOccurredAtDesc(
            UUID tenantId, SubjectType subjectType, UUID subjectId);

    Flux<Activity> findAllByTenantIdAndTypeAndSubjectTypeAndSubjectIdOrderByOccurredAtDesc(
            UUID tenantId, ActivityType type, SubjectType subjectType, UUID subjectId);
}
