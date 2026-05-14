package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.files.Attachment;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface AttachmentRepository extends TenantScopedReactiveMongoRepository<Attachment, UUID> {
    Flux<Attachment> findAllByTenantIdAndSubjectTypeAndSubjectIdOrderByCreatedAtDesc(
            UUID tenantId, String subjectType, UUID subjectId);
}
