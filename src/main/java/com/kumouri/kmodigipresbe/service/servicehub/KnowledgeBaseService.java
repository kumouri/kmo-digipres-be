package com.kumouri.kmodigipresbe.service.servicehub;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.servicehub.KnowledgeBaseArticle;
import com.kumouri.kmodigipresbe.repository.KnowledgeBaseArticleRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseArticleRepository repo;
    private final ReactiveMongoTemplate mongo;
    private final DomainEventPublisher events;

    public Flux<KnowledgeBaseArticle> findAll() {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> repo.findAllByTenantId(ctx.tenantId()));
    }

    public Mono<KnowledgeBaseArticle> findById(UUID id) {
        return TenantContextHolder.required()
                .flatMap(ctx -> repo.findByTenantIdAndId(ctx.tenantId(), id))
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException("KB article not found", 2920, 404)));
    }

    public Mono<KnowledgeBaseArticle> create(KnowledgeBaseArticle body) {
        return TenantContextHolder.required().flatMap(ctx ->
                repo.existsByTenantIdAndSlug(ctx.tenantId(), body.getSlug())
                        .flatMap(exists -> {
                            if (Boolean.TRUE.equals(exists)) {
                                return Mono.error(new DigiPresBeException(
                                        "KB article slug already exists", 2921, 409));
                            }
                            body.setId(null);
                            body.setTenantId(ctx.tenantId());
                            body.setEmbedding(null);
                            return repo.save(body)
                                    .flatMap(saved -> {
                                        events.publish(new DomainEvent(
                                                DomainEventType.KB_ARTICLE_CREATED,
                                                saved.getTenantId(), saved.getId(),
                                                Map.of("slug", saved.getSlug(), "title", saved.getTitle() == null ? "" : saved.getTitle()),
                                                Instant.now()));
                                        return Mono.just(saved);
                                    });
                        }));
    }

    public Mono<KnowledgeBaseArticle> update(UUID id, KnowledgeBaseArticle patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getTitle() != null) existing.setTitle(patch.getTitle());
            if (patch.getBody() != null) existing.setBody(patch.getBody());
            if (patch.getTags() != null) existing.setTags(patch.getTags());
            return repo.save(existing)
                    .flatMap(saved -> {
                        events.publish(new DomainEvent(
                                DomainEventType.KB_ARTICLE_UPDATED,
                                saved.getTenantId(), saved.getId(),
                                Map.of("slug", saved.getSlug()),
                                Instant.now()));
                        return Mono.just(saved);
                    });
        });
    }

    public Mono<KnowledgeBaseArticle> publish(UUID id) {
        return findById(id).flatMap(existing -> {
            if (existing.getBody() == null || existing.getBody().isBlank()) {
                return Mono.error(new DigiPresBeException("Cannot publish article with empty body", 2922, 422));
            }
            existing.setPublishedAt(Instant.now());
            return repo.save(existing);
        });
    }

    public Mono<Void> delete(UUID id) {
        return findById(id).flatMap(a -> repo.deleteById(a.getId()));
    }

    public Flux<KnowledgeBaseArticle> search(String query) {
        return TenantContextHolder.required().flatMapMany(ctx -> {
            // Security fix BE-17: neutralize MongoDB $text search operators in the raw user query.
            // A leading "-" means term-exclusion and double-quotes mean exact-phrase; stripping them
            // makes the query a plain term match so a user cannot manipulate result ranking/scope with
            // operator syntax. (Within-tenant only — the TextCriteria is AND-ed with tenantId, so this
            // was never a cross-tenant or injection risk; this is search-relevance hardening.)
            TextCriteria text = TextCriteria.forDefaultLanguage().matching(sanitizeTextQuery(query));
            Query q = Query.query(
                    Criteria.where("tenantId").is(ctx.tenantId())
                            .and("publishedAt").ne(null)
            ).addCriteria(text);
            return mongo.find(q, KnowledgeBaseArticle.class);
        });
    }

    /**
     * Security fix BE-17: strip {@code $text} operator syntax — double quotes (exact-phrase) and
     * leading {@code -} on each token (term-exclusion) — leaving plain search terms. Null/blank in →
     * "" out (TextCriteria treats an empty string as match-all within the other criteria).
     */
    static String sanitizeTextQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String noQuotes = query.replace("\"", " ");
        StringBuilder sb = new StringBuilder();
        for (String token : noQuotes.trim().split("\\s+")) {
            String cleaned = token;
            // Drop any leading '-' (and '+') operator prefixes on the token.
            while (!cleaned.isEmpty() && (cleaned.charAt(0) == '-' || cleaned.charAt(0) == '+')) {
                cleaned = cleaned.substring(1);
            }
            if (!cleaned.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(cleaned);
            }
        }
        return sb.toString();
    }

    public Mono<KnowledgeBaseArticle> findPublishedBySlug(UUID tenantId, String slug) {
        return repo.findByTenantIdAndSlug(tenantId, slug)
                .filter(a -> a.getPublishedAt() != null)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException("KB article not found", 2920, 404)));
    }
}
