package com.kumouri.kmodigipresbe.controller;

import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.billing.Payment;
import com.kumouri.kmodigipresbe.service.billing.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService service;

    @GetMapping
    public Flux<Invoice> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public Mono<Invoice> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Invoice> create(@RequestBody Invoice body) {
        return service.create(body);
    }

    @PostMapping("/from-quote/{quoteId}")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Invoice> fromQuote(@PathVariable UUID quoteId) {
        return service.createFromQuote(quoteId);
    }

    @PostMapping("/{id}/status")
    public Mono<Invoice> setStatus(@PathVariable UUID id, @RequestParam Invoice.Status target) {
        return service.setStatus(id, target);
    }

    @PostMapping("/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Payment> recordPayment(@PathVariable UUID id, @RequestBody Payment body) {
        body.setInvoiceId(id);
        return service.recordPayment(body);
    }

    @GetMapping("/{id}/payments")
    public Flux<Payment> payments(@PathVariable UUID id) {
        return service.paymentsFor(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return service.delete(id);
    }
}
