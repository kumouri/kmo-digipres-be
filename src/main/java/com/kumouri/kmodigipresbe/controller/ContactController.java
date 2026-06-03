package com.kumouri.kmodigipresbe.controller;

import com.kumouri.kmodigipresbe.model.request.ActivityDTO;
import com.kumouri.kmodigipresbe.model.request.ContactDTO;
import com.kumouri.kmodigipresbe.service.ContactCrudService;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.util.RequestMapper;
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
@RequestMapping("/contacts")
@RequiredArgsConstructor
public class ContactController {

    private final ContactCrudService service;
    private final RequestMapper mapper;

    /**
     * Broad reader (the full tenant contacts list) — {@code RoleGuard.denyRole("CONTRACTOR")}
     * (Phase J — J2) keeps a contractor off the CRM contact list; the only client info a
     * contractor sees is the read-only {@code GET /me/contractor/projects/{id}/client} for an
     * assigned project (→ 4135). Plain STAFF (non-contractor) employees are unaffected.
     */
    @GetMapping
    public Flux<ContactDTO> list() {
        return RoleGuard.denyRole("CONTRACTOR").thenMany(service.findAll().map(mapper::toContactDTO));
    }

    @GetMapping("/{id}")
    public Mono<ContactDTO> get(@PathVariable UUID id) {
        return service.findById(id).map(mapper::toContactDTO);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ContactDTO> create(@RequestBody ContactDTO body) {
        return service.create(mapper.toContact(body)).map(mapper::toContactDTO);
    }

    @PutMapping("/{id}")
    public Mono<ContactDTO> update(@PathVariable UUID id, @RequestBody ContactDTO body) {
        return service.update(id, mapper.toContact(body)).map(mapper::toContactDTO);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return service.delete(id);
    }

    @GetMapping("/{id}/timeline")
    public Flux<ActivityDTO> timeline(@PathVariable UUID id) {
        return service.timeline(id).map(mapper::toActivityDTO);
    }
}
