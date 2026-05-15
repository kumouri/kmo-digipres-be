package com.kumouri.kmodigipresbe.controller.marketing;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.marketing.LandingPage;
import com.kumouri.kmodigipresbe.repository.marketing.LandingPageRepository;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/landing-pages")
@RequiredArgsConstructor
public class LandingPageController {

    private final LandingPageRepository pages;

    @GetMapping
    public Flux<LandingPage> list() {
        return pages.findAll();
    }

    @GetMapping("/{id}")
    public Mono<LandingPage> get(@PathVariable UUID id) {
        return pages.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Landing page not found", 3100, 404)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<LandingPage> create(@RequestBody LandingPage body) {
        return RoleGuard.requireRole("ADMIN").then(Mono.defer(() -> {
            body.setId(null);
            return pages.save(body);
        }));
    }

    @PutMapping("/{id}")
    public Mono<LandingPage> update(@PathVariable UUID id,
                                     @RequestBody LandingPage patch) {
        return RoleGuard.requireRole("ADMIN")
                .then(pages.findById(id))
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Landing page not found", 3100, 404)))
                .flatMap(existing -> {
                    if (patch.getSlug() != null) existing.setSlug(patch.getSlug());
                    if (patch.getTitle() != null) existing.setTitle(patch.getTitle());
                    if (patch.getMustacheTemplate() != null)
                        existing.setMustacheTemplate(patch.getMustacheTemplate());
                    if (patch.getFormId() != null) existing.setFormId(patch.getFormId());
                    if (patch.getPublishedAt() != null) existing.setPublishedAt(patch.getPublishedAt());
                    return pages.save(existing);
                });
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return RoleGuard.requireRole("ADMIN").then(pages.deleteById(id));
    }
}
