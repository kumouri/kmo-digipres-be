package com.kumouri.kmodigipresbe.service.quote;

import com.kumouri.kmodigipresbe.config.FileStorageProperties;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.quote.Quote;
import com.kumouri.kmodigipresbe.repository.QuoteRepository;
import com.kumouri.kmodigipresbe.service.storage.FileStorageService;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QuoteService {

    private final QuoteRepository quotes;
    private final QuotePdfService pdf;
    private final FileStorageService storage;
    private final FileStorageProperties storageProps;

    public Flux<Quote> findAll() {
        return quotes.findAll();
    }

    public Mono<Quote> findById(UUID id) {
        return quotes.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Quote not found", 2200, 404)));
    }

    public Mono<Quote> create(Quote toCreate) {
        toCreate.setId(null);
        toCreate.setStatusChangedAt(Instant.now());
        toCreate.computeTotals();
        return quotes.save(toCreate);
    }

    public Mono<Quote> update(UUID id, Quote patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getQuoteNumber() != null) existing.setQuoteNumber(patch.getQuoteNumber());
            if (patch.getDealId() != null) existing.setDealId(patch.getDealId());
            if (patch.getContactId() != null) existing.setContactId(patch.getContactId());
            if (patch.getCompanyId() != null) existing.setCompanyId(patch.getCompanyId());
            if (patch.getPriceListId() != null) existing.setPriceListId(patch.getPriceListId());
            if (patch.getCurrency() != null) existing.setCurrency(patch.getCurrency());
            if (patch.getLineItems() != null) existing.setLineItems(patch.getLineItems());
            if (patch.getNotes() != null) existing.setNotes(patch.getNotes());
            if (patch.getTerms() != null) existing.setTerms(patch.getTerms());
            if (patch.getIssuedAt() != null) existing.setIssuedAt(patch.getIssuedAt());
            if (patch.getExpiresAt() != null) existing.setExpiresAt(patch.getExpiresAt());
            if (patch.getCustomFields() != null) existing.setCustomFields(patch.getCustomFields());
            existing.computeTotals();
            return quotes.save(existing);
        });
    }

    public Mono<Quote> setStatus(UUID id, Quote.Status target) {
        return findById(id).flatMap(existing -> {
            existing.setStatus(target);
            existing.setStatusChangedAt(Instant.now());
            return quotes.save(existing);
        });
    }

    public Mono<Void> delete(UUID id) {
        return quotes.deleteById(id);
    }

    /**
     * Render a PDF and return the bytes directly. A separate {@link #generateAndStorePdf}
     * stores the bytes and records the resulting {@code pdfStorageRef} on the quote.
     */
    public Mono<byte[]> renderPdfBytes(UUID id) {
        return findById(id).flatMap(pdf::render);
    }

    public Mono<FileStorageService.Presigned> presignPdfUpload(UUID id) {
        return TenantContextHolder.required().flatMap(ctx -> findById(id).map(q ->
                storage.presignUpload(
                        ctx.tenantId(),
                        "quotes/" + q.getId(),
                        "application/pdf",
                        "pdf",
                        Duration.ofSeconds(storageProps.uploadTtlSeconds()))));
    }

    public Mono<Quote> recordPdfStorageRef(UUID id, String storageRef) {
        return findById(id).flatMap(q -> TenantContextHolder.required().flatMap(ctx -> {
            String prefix = "tenants/" + ctx.tenantId() + "/";
            if (!storageRef.startsWith(prefix)) {
                return Mono.error(new DigiPresBeException(
                        "storageRef does not belong to this tenant", 2201, 403));
            }
            q.setPdfStorageRef(storageRef);
            return quotes.save(q);
        }));
    }
}
