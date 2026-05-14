package com.kumouri.kmodigipresbe.module.fieldservice.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.fieldservice.FieldServiceAutoConfiguration;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.fieldservice.service.WorkOrderService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/work-orders")
@ConditionalOnProperty(prefix = "kmosf.modules.field-service", name = "enabled")
@RequiredArgsConstructor
public class WorkOrderController {

    private final WorkOrderService service;
    private final TenantModuleRegistry modules;

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CompleteRequest {
        private String signatureRef;
        private List<String> photoRefs;
    }

    @GetMapping
    public Flux<WorkOrder> list() {
        return guard().thenMany(service.findAll());
    }

    @GetMapping("/{id}")
    public Mono<WorkOrder> get(@PathVariable UUID id) {
        return guard().then(service.findById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<WorkOrder> create(@RequestBody WorkOrder body) {
        return guard().then(service.create(body));
    }

    @PutMapping("/{id}")
    public Mono<WorkOrder> update(@PathVariable UUID id, @RequestBody WorkOrder body) {
        return guard().then(service.update(id, body));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return guard().then(service.delete(id));
    }

    @GetMapping("/upcoming")
    public Flux<WorkOrder> upcoming(@RequestParam Instant from, @RequestParam Instant to) {
        return guard().thenMany(service.upcoming(from, to));
    }

    @PostMapping("/{id}:complete")
    public Mono<WorkOrder> complete(@PathVariable UUID id, @RequestBody CompleteRequest body) {
        return guard().then(service.complete(id, body.getSignatureRef(), body.getPhotoRefs()));
    }

    @GetMapping("/{id}/expand")
    public Flux<WorkOrder> expand(@PathVariable UUID id,
                                  @RequestParam Instant from,
                                  @RequestParam Instant to) {
        return guard().thenMany(service.expandRecurrence(id, from, to));
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(FieldServiceAutoConfiguration.MODULE_KEY);
    }
}
