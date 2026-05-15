package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.servicehub.KnowledgeBaseArticle;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface KnowledgeBaseArticleRepository extends TenantScopedReactiveMongoRepository<KnowledgeBaseArticle, UUID> {

    Flux<KnowledgeBaseArticle> findAllByTenantId(UUID tenantId);

    Mono<KnowledgeBaseArticle> findByTenantIdAndId(UUID tenantId, UUID id);

    Mono<KnowledgeBaseArticle> findByTenantIdAndSlug(UUID tenantId, String slug);

    Mono<Boolean> existsByTenantIdAndSlug(UUID tenantId, String slug);
}
