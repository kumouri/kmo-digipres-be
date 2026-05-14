package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.template.EmailTemplate;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface EmailTemplateRepository
        extends TenantScopedReactiveMongoRepository<EmailTemplate, UUID> {

    Mono<EmailTemplate> findByTenantIdAndName(UUID tenantId, String name);
}
