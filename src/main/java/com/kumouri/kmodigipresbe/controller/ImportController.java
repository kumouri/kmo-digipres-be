package com.kumouri.kmodigipresbe.controller;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.imports.ImportJob;
import com.kumouri.kmodigipresbe.repository.ImportJobRepository;
import com.kumouri.kmodigipresbe.service.imports.CsvImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/imports")
@RequiredArgsConstructor
public class ImportController {

    private final CsvImportService csvImport;
    private final ImportJobRepository jobs;

    @PostMapping("/contacts")
    public Mono<ImportJob> importContacts(@RequestPart("file") Mono<FilePart> file) {
        return file.flatMap(part -> {
            Flux<DataBuffer> content = part.content();
            return csvImport.importContacts(content);
        });
    }

    @GetMapping("/{id}")
    public Mono<ImportJob> get(@PathVariable UUID id) {
        return jobs.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "ImportJob not found", 1810, 404)));
    }
}
