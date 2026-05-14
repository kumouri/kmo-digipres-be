package com.kumouri.kmodigipresbe.controller;

import com.kumouri.kmodigipresbe.model.meeting.Meeting;
import com.kumouri.kmodigipresbe.service.MeetingCrudService;
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
@RequestMapping("/meetings")
@RequiredArgsConstructor
public class MeetingController {

    private final MeetingCrudService service;

    @GetMapping
    public Flux<Meeting> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public Mono<Meeting> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Meeting> create(@RequestBody Meeting body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public Mono<Meeting> update(@PathVariable UUID id, @RequestBody Meeting body) {
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return service.delete(id);
    }
}
