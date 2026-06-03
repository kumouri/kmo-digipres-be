package com.kumouri.kmodigipresbe.controller;

import com.kumouri.kmodigipresbe.model.request.DealDTO;
import com.kumouri.kmodigipresbe.model.request.MoveStageRequest;
import com.kumouri.kmodigipresbe.service.DealCrudService;
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
@RequestMapping("/deals")
@RequiredArgsConstructor
public class DealController {

    private final DealCrudService service;
    private final RequestMapper mapper;

    /**
     * Broad reader (the sales pipeline) — {@code RoleGuard.denyRole("CONTRACTOR")} (Phase J —
     * J2) keeps a contractor entirely out of deals/pipeline (→ 4135). Plain STAFF
     * (non-contractor) employees are unaffected.
     */
    @GetMapping
    public Flux<DealDTO> list() {
        return RoleGuard.denyRole("CONTRACTOR").thenMany(service.findAll().map(mapper::toDealDTO));
    }

    @GetMapping("/{id}")
    public Mono<DealDTO> get(@PathVariable UUID id) {
        return service.findById(id).map(mapper::toDealDTO);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<DealDTO> create(@RequestBody DealDTO body) {
        return service.create(mapper.toDeal(body)).map(mapper::toDealDTO);
    }

    @PutMapping("/{id}")
    public Mono<DealDTO> update(@PathVariable UUID id, @RequestBody DealDTO body) {
        return service.update(id, mapper.toDeal(body)).map(mapper::toDealDTO);
    }

    @PostMapping("/{id}/move")
    public Mono<DealDTO> move(@PathVariable UUID id, @RequestBody MoveStageRequest request) {
        return service.moveStage(id, request.getStage(), request.getLostReason())
                .map(mapper::toDealDTO);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return service.delete(id);
    }
}
