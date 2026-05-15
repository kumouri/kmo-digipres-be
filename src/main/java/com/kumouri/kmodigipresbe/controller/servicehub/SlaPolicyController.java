package com.kumouri.kmodigipresbe.controller.servicehub;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.servicehub.SlaPolicy;
import com.kumouri.kmodigipresbe.repository.SlaPolicyRepository;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/admin/sla-policies")
@ConditionalOnProperty(prefix = "kmosf.modules.service-hub", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class SlaPolicyController {

    private final SlaPolicyRepository policies;

    @GetMapping
    public Flux<SlaPolicy> list() {
        return guard().thenMany(
                TenantContextHolder.required()
                        .flatMapMany(ctx -> policies.findAllByTenantId(ctx.tenantId())));
    }

    @GetMapping("/{id}")
    public Mono<SlaPolicy> get(@PathVariable UUID id) {
        return guard().then(
                TenantContextHolder.required()
                        .flatMap(ctx -> policies.findByTenantIdAndId(ctx.tenantId(), id))
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "SLA policy not found", 2902, 404))));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<SlaPolicy> create(@RequestBody SlaPolicy body) {
        return guard().then(
                TenantContextHolder.required().flatMap(ctx -> {
                    body.setId(null);
                    body.setTenantId(ctx.tenantId());
                    return policies.save(body);
                }));
    }

    @PutMapping("/{id}")
    public Mono<SlaPolicy> update(@PathVariable UUID id, @RequestBody SlaPolicy body) {
        return guard().then(
                TenantContextHolder.required().flatMap(ctx ->
                        policies.findByTenantIdAndId(ctx.tenantId(), id)
                                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                        "SLA policy not found", 2902, 404)))
                                .flatMap(existing -> {
                                    if (body.getName() != null) existing.setName(body.getName());
                                    if (body.getResponseTargetMinutes() != null)
                                        existing.setResponseTargetMinutes(body.getResponseTargetMinutes());
                                    if (body.getResolutionTargetMinutes() != null)
                                        existing.setResolutionTargetMinutes(body.getResolutionTargetMinutes());
                                    return policies.save(existing);
                                })));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return guard().then(
                TenantContextHolder.required().flatMap(ctx ->
                        policies.findByTenantIdAndId(ctx.tenantId(), id)
                                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                        "SLA policy not found", 2902, 404)))
                                .flatMap(p -> policies.deleteById(p.getId()))));
    }

    private Mono<Void> guard() {
        return RoleGuard.requireRole("ADMIN");
    }
}
