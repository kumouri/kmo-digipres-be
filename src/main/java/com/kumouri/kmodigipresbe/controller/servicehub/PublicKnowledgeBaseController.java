package com.kumouri.kmodigipresbe.controller.servicehub;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.servicehub.KnowledgeBaseArticle;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.service.servicehub.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/public/kb")
@ConditionalOnProperty(prefix = "kmosf.modules.service-hub", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class PublicKnowledgeBaseController {

    private final TenantRepository tenants;
    private final KnowledgeBaseService service;

    @GetMapping("/{tenantSlug}/{slug}")
    public Mono<KnowledgeBaseArticle> get(
            @PathVariable String tenantSlug,
            @PathVariable String slug) {
        return tenants.findBySlug(tenantSlug)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException("Unknown tenant", 1201, 404)))
                .flatMap(tenant -> service.findPublishedBySlug(tenant.getId(), slug));
    }
}
