package com.kumouri.kmodigipresbe.controller;

import com.kumouri.kmodigipresbe.model.request.ActivityDTO;
import com.kumouri.kmodigipresbe.service.ActivityCrudService;
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
@RequestMapping("/activities")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityCrudService service;
    private final RequestMapper mapper;

    @GetMapping
    public Flux<ActivityDTO> list() {
        return service.findAll().map(mapper::toActivityDTO);
    }

    @GetMapping("/{id}")
    public Mono<ActivityDTO> get(@PathVariable UUID id) {
        return service.findById(id).map(mapper::toActivityDTO);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ActivityDTO> create(@RequestBody ActivityDTO body) {
        return service.create(mapper.toActivity(body)).map(mapper::toActivityDTO);
    }

    @PutMapping("/{id}")
    public Mono<ActivityDTO> update(@PathVariable UUID id, @RequestBody ActivityDTO body) {
        return service.update(id, mapper.toActivity(body)).map(mapper::toActivityDTO);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return service.delete(id);
    }
}
