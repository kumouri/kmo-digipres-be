package com.kumouri.kmodigipresbe.module.homeservices.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.homeservices.HomeServicesAutoConfiguration;
import com.kumouri.kmodigipresbe.module.homeservices.model.Equipment;
import com.kumouri.kmodigipresbe.module.homeservices.service.EquipmentService;
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
 * Equipment CRUD + JobSite-scoped lookup. DELETE is admin-only and explicitly
 * audited (Spring Data MongoDB has no reactive delete callback). List is
 * unpaginated for now per {@code CLAUDE.md} — revisit when any tenant crosses
 * ~500 rows.
 */
@RestController
@RequestMapping("/home-services/equipment")
@ConditionalOnProperty(prefix = "kmosf.modules.home-services", name = "enabled")
@RequiredArgsConstructor
public class EquipmentController {

    private final EquipmentService service;
    private final TenantModuleRegistry modules;

    @GetMapping
    public Flux<Equipment> list() {
        return guard().thenMany(service.findAll());
    }

    @GetMapping("/{id}")
    public Mono<Equipment> get(@PathVariable UUID id) {
        return guard().then(service.findById(id));
    }

    @GetMapping("/by-job-site/{jobSiteId}")
    public Flux<Equipment> byJobSite(@PathVariable UUID jobSiteId) {
        return guard().thenMany(service.findByJobSite(jobSiteId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Equipment> create(@RequestBody Equipment body) {
        return guard().then(service.create(body));
    }

    @PutMapping("/{id}")
    public Mono<Equipment> update(@PathVariable UUID id, @RequestBody Equipment body) {
        return guard().then(service.update(id, body));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return guard()
                .then(RoleGuard.requireRole("ADMIN"))
                .then(service.delete(id));
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(HomeServicesAutoConfiguration.MODULE_KEY);
    }
}
