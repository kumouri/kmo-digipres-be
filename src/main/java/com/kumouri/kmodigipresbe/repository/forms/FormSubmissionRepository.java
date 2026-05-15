package com.kumouri.kmodigipresbe.repository.forms;

import com.kumouri.kmodigipresbe.model.forms.FormSubmission;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface FormSubmissionRepository
        extends TenantScopedReactiveMongoRepository<FormSubmission, UUID> {

    Flux<FormSubmission> findByTenantIdAndFormIdOrderBySubmittedAtDesc(UUID tenantId, UUID formId);
}
