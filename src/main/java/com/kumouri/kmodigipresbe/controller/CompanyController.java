package com.kumouri.kmodigipresbe.controller;

import com.kumouri.kmodigipresbe.model.request.CompanyDTO;
import com.kumouri.kmodigipresbe.service.CompanyCrudService;
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
@RequestMapping("/companies")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyCrudService service;
    private final RequestMapper mapper;

    @GetMapping
    public Flux<CompanyDTO> list() {
        return service.findAll().map(mapper::toCompanyDTO);
    }

    @GetMapping("/{id}")
    public Mono<CompanyDTO> get(@PathVariable UUID id) {
        return service.findById(id).map(mapper::toCompanyDTO);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<CompanyDTO> create(@RequestBody CompanyDTO body) {
        return service.create(mapper.toCompany(body)).map(mapper::toCompanyDTO);
    }

    @PutMapping("/{id}")
    public Mono<CompanyDTO> update(@PathVariable UUID id, @RequestBody CompanyDTO body) {
        return service.update(id, mapper.toCompany(body)).map(mapper::toCompanyDTO);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return service.delete(id);
    }
}
