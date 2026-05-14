package com.kumouri.kmodigipresbe.controller.integration;

import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionService;
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
@RequestMapping("/integrations/connections")
@RequiredArgsConstructor
public class IntegrationConnectionController {

    private final IntegrationConnectionService service;

    @GetMapping
    public Flux<IntegrationConnection> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public Mono<IntegrationConnection> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @GetMapping("/by-provider/{provider}")
    public Mono<IntegrationConnection> getByProvider(@PathVariable String provider) {
        return service.findByProvider(provider);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<IntegrationConnection> upsert(@RequestBody IntegrationConnection body) {
        return service.upsert(body);
    }

    @PutMapping("/{id}")
    public Mono<IntegrationConnection> update(@PathVariable UUID id,
                                              @RequestBody IntegrationConnection body) {
        return service.upsert(body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return service.delete(id);
    }
}
