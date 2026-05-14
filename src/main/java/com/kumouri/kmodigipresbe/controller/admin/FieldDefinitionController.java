package com.kumouri.kmodigipresbe.controller.admin;

import com.kumouri.kmodigipresbe.extension.FieldDefinition;
import com.kumouri.kmodigipresbe.extension.FieldDefinitionService;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("/admin/field-definitions")
@RequiredArgsConstructor
public class FieldDefinitionController {

    private final FieldDefinitionService service;

    @GetMapping
    public Flux<FieldDefinition> list(@RequestParam(required = false) String entityType) {
        return entityType == null ? service.listAll() : service.listByEntity(entityType);
    }

    @GetMapping("/{id}")
    public Mono<FieldDefinition> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<FieldDefinition> create(@RequestBody FieldDefinition body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public Mono<FieldDefinition> update(@PathVariable UUID id, @RequestBody FieldDefinition body) {
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return service.delete(id);
    }
}
