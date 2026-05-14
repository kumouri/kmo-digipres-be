package com.kumouri.kmodigipresbe.controller.sequence;

import com.kumouri.kmodigipresbe.model.sequence.Sequence;
import com.kumouri.kmodigipresbe.model.sequence.SequenceEnrollment;
import com.kumouri.kmodigipresbe.service.sequence.EnrollRequest;
import com.kumouri.kmodigipresbe.service.sequence.SequenceCrudService;
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
@RequestMapping("/sequences")
@RequiredArgsConstructor
public class SequenceController {

    private final SequenceCrudService service;

    @GetMapping
    public Flux<Sequence> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public Mono<Sequence> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Sequence> create(@RequestBody Sequence body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public Mono<Sequence> update(@PathVariable UUID id, @RequestBody Sequence body) {
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return service.delete(id);
    }

    @PostMapping("/{id}/enroll")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<SequenceEnrollment> enroll(@PathVariable UUID id, @RequestBody EnrollRequest body) {
        return service.enroll(id, body.contactId());
    }

    @PostMapping("/{id}/unenroll")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> unenroll(@PathVariable UUID id, @RequestBody EnrollRequest body) {
        return service.unenroll(id, body.contactId());
    }
}
