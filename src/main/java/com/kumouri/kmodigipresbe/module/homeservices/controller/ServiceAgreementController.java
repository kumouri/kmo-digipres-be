package com.kumouri.kmodigipresbe.module.homeservices.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.homeservices.HomeServicesAutoConfiguration;
import com.kumouri.kmodigipresbe.module.homeservices.model.ServiceAgreement;
import com.kumouri.kmodigipresbe.module.homeservices.service.ServiceAgreementService;
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
@RequestMapping("/home-services/service-agreements")
@ConditionalOnProperty(prefix = "kmosf.modules.home-services", name = "enabled")
@RequiredArgsConstructor
public class ServiceAgreementController {

    private final ServiceAgreementService service;
    private final TenantModuleRegistry modules;

    @GetMapping
    public Flux<ServiceAgreement> list() {
        return guard().thenMany(service.findAll());
    }

    @GetMapping("/{id}")
    public Mono<ServiceAgreement> get(@PathVariable UUID id) {
        return guard().then(service.findById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ServiceAgreement> create(@RequestBody ServiceAgreement body) {
        return guard().then(service.create(body));
    }

    @PutMapping("/{id}")
    public Mono<ServiceAgreement> update(@PathVariable UUID id, @RequestBody ServiceAgreement body) {
        return guard().then(service.update(id, body));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return guard().then(service.delete(id));
    }

    @PostMapping("/{id}/activate")
    public Mono<ServiceAgreement> activate(@PathVariable UUID id) {
        return guard().then(service.activate(id));
    }

    @PostMapping("/{id}/pause")
    public Mono<ServiceAgreement> pause(@PathVariable UUID id) {
        return guard().then(service.pause(id));
    }

    @PostMapping("/{id}/regenerate-visits")
    public Mono<ServiceAgreement> regenerateVisits(@PathVariable UUID id) {
        return guard().then(service.regenerateVisits(id));
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(HomeServicesAutoConfiguration.MODULE_KEY);
    }
}
