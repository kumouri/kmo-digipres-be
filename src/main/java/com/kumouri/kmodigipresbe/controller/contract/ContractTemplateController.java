package com.kumouri.kmodigipresbe.controller.contract;

import com.kumouri.kmodigipresbe.model.contract.ContractTemplate;
import com.kumouri.kmodigipresbe.service.contract.ContractTemplateService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * REST API for {@link ContractTemplate} resources (Phase F — F-D12).
 *
 * <p>Module-gated {@code @ConditionalOnProperty(kmosf.modules.contracts.enabled,
 * matchIfMissing=true)} (F-D13). Raw-entity in/out — no MapStruct DTOs (the
 * codebase convention). {@code DELETE} gated by {@link RoleGuard#requireRole(String)}
 * (the Phase-C/D/E ADMIN-only delete pattern).
 *
 * <p>Templates have no state machine; validation errors: {@code 3701} name blank,
 * {@code 3702} bodyTemplate blank, {@code 3705} not found/inactive.
 */
@RestController
@RequestMapping("/contract-templates")
@ConditionalOnProperty(prefix = "kmosf.modules.contracts", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class ContractTemplateController {

    private final ContractTemplateService service;

    @GetMapping
    public Flux<ContractTemplate> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public Mono<ContractTemplate> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ContractTemplate> create(@RequestBody ContractTemplate body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public Mono<ContractTemplate> update(@PathVariable UUID id,
                                         @RequestBody ContractTemplate body) {
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return RoleGuard.requireRole("ADMIN").then(service.delete(id));
    }
}
