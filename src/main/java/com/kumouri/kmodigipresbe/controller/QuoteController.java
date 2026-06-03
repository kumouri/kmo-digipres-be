package com.kumouri.kmodigipresbe.controller;

import com.kumouri.kmodigipresbe.model.quote.Quote;
import com.kumouri.kmodigipresbe.service.quote.QuoteService;
import com.kumouri.kmodigipresbe.service.storage.FileStorageService;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/quotes")
@RequiredArgsConstructor
public class QuoteController {

    private final QuoteService service;

    /**
     * Broad reader (all quotes / sales financials) — {@code RoleGuard.denyRole("CONTRACTOR")}
     * (Phase J — J2) keeps a contractor out of quotes (→ 4135). Plain STAFF (non-contractor)
     * employees are unaffected.
     */
    @GetMapping
    public Flux<Quote> list() {
        return RoleGuard.denyRole("CONTRACTOR").thenMany(service.findAll());
    }

    @GetMapping("/{id}")
    public Mono<Quote> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping
    public Mono<Quote> create(@RequestBody Quote body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public Mono<Quote> update(@PathVariable UUID id, @RequestBody Quote body) {
        return service.update(id, body);
    }

    @PostMapping("/{id}/status")
    public Mono<Quote> setStatus(@PathVariable UUID id, @RequestParam Quote.Status target) {
        return service.setStatus(id, target);
    }

    @DeleteMapping("/{id}")
    public Mono<Void> delete(@PathVariable UUID id) {
        return service.delete(id);
    }

    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public Mono<ResponseEntity<Flux<DataBuffer>>> pdf(@PathVariable UUID id) {
        return service.renderPdfBytes(id).map(bytes -> {
            DataBuffer buf = new DefaultDataBufferFactory().wrap(bytes);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"quote-" + id + ".pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(Flux.just(buf));
        });
    }

    @PostMapping("/{id}/pdf/presign")
    public Mono<FileStorageService.Presigned> presignPdf(@PathVariable UUID id) {
        return service.presignPdfUpload(id);
    }

    @PostMapping("/{id}/pdf/ref")
    public Mono<Quote> recordPdfRef(@PathVariable UUID id, @RequestParam String storageRef) {
        return service.recordPdfStorageRef(id, storageRef);
    }
}
