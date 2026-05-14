package com.kumouri.kmodigipresbe.module.fieldservice.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.fieldservice.FieldServiceAutoConfiguration;
import com.kumouri.kmodigipresbe.module.fieldservice.model.JobSite;
import com.kumouri.kmodigipresbe.module.fieldservice.service.JobSiteService;
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

import java.util.UUID;

@RestController
@RequestMapping("/job-sites")
@ConditionalOnProperty(prefix = "kmosf.modules.field-service", name = "enabled")
@RequiredArgsConstructor
public class JobSiteController {

    private final JobSiteService service;
    private final TenantModuleRegistry modules;

    @GetMapping
    public Flux<JobSite> list() {
        return guard().thenMany(service.findAll());
    }

    @GetMapping("/near")
    public Flux<JobSite> near(@RequestParam double lat,
                              @RequestParam double lng,
                              @RequestParam(defaultValue = "25") double radiusKm) {
        return guard().thenMany(service.near(lat, lng, radiusKm));
    }

    @GetMapping("/{id}")
    public Mono<JobSite> get(@PathVariable UUID id) {
        return guard().then(service.findById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<JobSite> create(@RequestBody JobSite body) {
        return guard().then(service.create(body));
    }

    @PutMapping("/{id}")
    public Mono<JobSite> update(@PathVariable UUID id, @RequestBody JobSite body) {
        return guard().then(service.update(id, body));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return guard().then(service.delete(id));
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(FieldServiceAutoConfiguration.MODULE_KEY);
    }
}
