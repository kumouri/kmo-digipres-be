package com.kumouri.kmodigipresbe.controller.servicehub;

import com.kumouri.kmodigipresbe.model.servicehub.KnowledgeBaseArticle;
import com.kumouri.kmodigipresbe.service.servicehub.KnowledgeBaseService;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/knowledge-base")
@ConditionalOnProperty(prefix = "kmosf.modules.service-hub", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService service;

    @GetMapping("/articles")
    public Flux<KnowledgeBaseArticle> list() {
        return service.findAll();
    }

    @GetMapping("/articles/{id}")
    public Mono<KnowledgeBaseArticle> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping("/articles")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<KnowledgeBaseArticle> create(@RequestBody KnowledgeBaseArticle body) {
        return RoleGuard.requireRole("ADMIN").then(service.create(body));
    }

    @PutMapping("/articles/{id}")
    public Mono<KnowledgeBaseArticle> update(@PathVariable UUID id, @RequestBody KnowledgeBaseArticle body) {
        return RoleGuard.requireRole("ADMIN").then(service.update(id, body));
    }

    @PostMapping("/articles/{id}/publish")
    public Mono<KnowledgeBaseArticle> publish(@PathVariable UUID id) {
        return RoleGuard.requireRole("ADMIN").then(service.publish(id));
    }

    @DeleteMapping("/articles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return RoleGuard.requireRole("ADMIN").then(service.delete(id));
    }

    @PostMapping("/search")
    public Flux<KnowledgeBaseArticle> search(@RequestBody Map<String, String> body) {
        String query = body.getOrDefault("query", "");
        return service.search(query);
    }
}
