package com.kumouri.kmodigipresbe.controller.compliance;

import com.kumouri.kmodigipresbe.model.compliance.ConsentRecord;
import com.kumouri.kmodigipresbe.service.compliance.GdprConsentService;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/contacts/{contactId}/consents")
@RequiredArgsConstructor
public class ConsentController {

    private final GdprConsentService consentService;

    public record RecordConsentRequest(String topic, String lawfulBasis,
                                       String source, String ipAddress) {}

    @GetMapping
    public Flux<ConsentRecord> list(@PathVariable UUID contactId) {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> consentService.listConsents(ctx.tenantId(), contactId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ConsentRecord> record(@PathVariable UUID contactId,
                                      @RequestBody RecordConsentRequest body) {
        return TenantContextHolder.required()
                .flatMap(ctx -> consentService.recordConsent(
                        ctx.tenantId(), contactId,
                        body.topic(), body.lawfulBasis(),
                        body.source(), body.ipAddress(),
                        ctx.userId()));
    }

    @DeleteMapping("/{consentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> withdraw(@PathVariable UUID contactId,
                               @PathVariable UUID consentId) {
        return TenantContextHolder.required()
                .flatMap(ctx -> consentService.withdraw(ctx.tenantId(), contactId, consentId));
    }
}
