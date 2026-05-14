package com.kumouri.kmodigipresbe.controller.automation;

import com.kumouri.kmodigipresbe.automation.webhook.WebhookDeliveryService;
import com.kumouri.kmodigipresbe.automation.webhook.WebhookSubscription;
import com.kumouri.kmodigipresbe.automation.webhook.WebhookSubscriptionRepository;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
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
@RequestMapping("/webhooks/subscriptions")
@RequiredArgsConstructor
public class WebhookSubscriptionController {

    private final WebhookSubscriptionRepository subs;
    private final WebhookDeliveryService delivery;

    @GetMapping
    public Flux<WebhookSubscription> list() {
        return subs.findAll();
    }

    @GetMapping("/{id}")
    public Mono<WebhookSubscription> get(@PathVariable UUID id) {
        return subs.findById(id).switchIfEmpty(Mono.error(() ->
                new DigiPresBeException("WebhookSubscription not found", 1910, 404)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<WebhookSubscription> create(@RequestBody WebhookSubscription body) {
        body.setId(null);
        return subs.save(body);
    }

    @PutMapping("/{id}")
    public Mono<WebhookSubscription> update(@PathVariable UUID id, @RequestBody WebhookSubscription body) {
        return subs.findById(id).flatMap(existing -> {
            if (body.getName() != null) existing.setName(body.getName());
            if (body.getUrl() != null) existing.setUrl(body.getUrl());
            if (body.getSecret() != null) existing.setSecret(body.getSecret());
            if (body.getEventTypes() != null) existing.setEventTypes(body.getEventTypes());
            existing.setActive(body.isActive());
            return subs.save(existing);
        }).switchIfEmpty(Mono.error(() ->
                new DigiPresBeException("WebhookSubscription not found", 1910, 404)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return subs.deleteById(id);
    }

    @PostMapping("/{id}:test")
    public Mono<Void> test(@PathVariable UUID id) {
        return subs.findById(id)
                .switchIfEmpty(Mono.error(() ->
                        new DigiPresBeException("WebhookSubscription not found", 1910, 404)))
                .flatMap(delivery::testDeliver);
    }
}
