package com.kumouri.kmodigipresbe.module.salonspa.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.salonspa.SalonSpaAutoConfiguration;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu;
import com.kumouri.kmodigipresbe.module.salonspa.service.SalonMenuService;
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

@RestController
@RequestMapping("/salon-spa/service-menus")
@ConditionalOnProperty(prefix = "kmosf.modules.salon-spa", name = "enabled")
@RequiredArgsConstructor
public class ServiceMenuController {

    private final SalonMenuService service;
    private final TenantModuleRegistry modules;

    @GetMapping
    public Flux<ServiceMenu> list() {
        return guard().thenMany(service.findAll());
    }

    @GetMapping("/{id}")
    public Mono<ServiceMenu> get(@PathVariable UUID id) {
        return guard().then(service.findById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ServiceMenu> create(@RequestBody ServiceMenu body) {
        return guard().then(RoleGuard.requireRole("ADMIN")).then(service.create(body));
    }

    @PutMapping("/{id}")
    public Mono<ServiceMenu> update(@PathVariable UUID id, @RequestBody ServiceMenu body) {
        return guard().then(RoleGuard.requireRole("ADMIN")).then(service.update(id, body));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return guard().then(RoleGuard.requireRole("ADMIN")).then(service.delete(id));
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(SalonSpaAutoConfiguration.MODULE_KEY);
    }
}
