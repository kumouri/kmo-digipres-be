package com.kumouri.kmodigipresbe.controller.servicehub;

import com.kumouri.kmodigipresbe.model.servicehub.Ticket;
import com.kumouri.kmodigipresbe.model.servicehub.TicketComment;
import com.kumouri.kmodigipresbe.model.servicehub.TicketStatus;
import com.kumouri.kmodigipresbe.service.servicehub.TicketService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/tickets")
@ConditionalOnProperty(prefix = "kmosf.modules.service-hub", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class TicketController {

    private final TicketService service;

    @GetMapping
    public Flux<Ticket> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public Mono<Ticket> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Ticket> create(@RequestBody Ticket body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public Mono<Ticket> update(@PathVariable UUID id, @RequestBody Ticket body) {
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return RoleGuard.requireRole("ADMIN").then(service.delete(id));
    }

    @PostMapping("/{id}/transition")
    public Mono<Ticket> transition(@PathVariable UUID id, @RequestParam String status) {
        TicketStatus newStatus = TicketStatus.valueOf(status.toUpperCase());
        return service.transition(id, newStatus);
    }

    @GetMapping("/{id}/comments")
    public Flux<TicketComment> listComments(@PathVariable UUID id) {
        return service.listComments(id);
    }

    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<TicketComment> addComment(@PathVariable UUID id, @RequestBody TicketComment body) {
        return service.addComment(id, body);
    }
}
