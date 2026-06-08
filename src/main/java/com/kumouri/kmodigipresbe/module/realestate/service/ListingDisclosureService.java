package com.kumouri.kmodigipresbe.module.realestate.service;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingDisclosure;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingDisclosureRepository;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingRepository;
import com.kumouri.kmodigipresbe.service.ai.embedding.EmbeddingService;
import com.kumouri.kmodigipresbe.service.ai.rag.RagRetrievalService;
import com.kumouri.kmodigipresbe.service.ai.vector.VectorIndex;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Real Estate Concierge (RE-1) — owns the {@link ListingDisclosure} CRUD <strong>and the
 * disclosure-text indexing</strong> (the RE-1 §3 / §6.3 crux).
 *
 * <p><strong>Why the module owns the embed+upsert (not {@code EmbeddingPipeline.indexAttachment}):</strong>
 * {@code indexAttachment} embeds only a file's filename+content-type, so a disclosure indexed that way
 * would never ground a real question. Instead, on create/update this service embeds the disclosure's
 * <em>text</em> directly and upserts it as embedding source type
 * {@link RagRetrievalService#LISTING_DISCLOSURE_SOURCE_TYPE "ListingDisclosure"} with {@code listingId}
 * metadata — the same direct-upsert shape {@code EmbeddingPipeline.embedAndUpsert} uses, just owned by the
 * module and carrying {@code listingId} (so {@code RagRetrievalService.retrieveForListing} can scope to one
 * listing and never bleed across listings or into generic CRM vectors). The core pipeline is untouched
 * (blast-radius zero), and the raw uploaded doc (if any) is still kept as an {@code Attachment} for the
 * agent to view.
 *
 * <p><strong>Best-effort indexing:</strong> the disclosure save always succeeds first; an embedding/upsert
 * failure logs {@code 4252} and leaves {@code indexedAt} null (a re-index can retry) — the disclosure is
 * never lost. On success {@code indexedAt} is stamped and {@code LISTING_DISCLOSURE_INDEXED} is emitted.
 *
 * <p>Hand-constructed as a {@code @Bean} by {@code RealEstateAutoConfiguration} (no {@code @Service}
 * annotation) so it exists only when the module is enabled.
 */
@Slf4j
public class ListingDisclosureService {

    /** Disclosure text is truncated to this before embedding (the {@code EmbeddingPipeline} cap). */
    static final int MAX_SNIPPET_CHARS = 8_000;
    /** The {@code contentPreview} metadata cap (matches {@code EmbeddingPipeline.MAX_PREVIEW_CHARS}). */
    static final int MAX_PREVIEW_CHARS = 500;

    private final ListingDisclosureRepository disclosures;
    private final ListingRepository listings;
    private final EmbeddingService embeddingService;
    private final VectorIndex vectorIndex;
    private final DomainEventPublisher events;

    public ListingDisclosureService(ListingDisclosureRepository disclosures,
                                    ListingRepository listings,
                                    EmbeddingService embeddingService,
                                    VectorIndex vectorIndex,
                                    DomainEventPublisher events) {
        this.disclosures = disclosures;
        this.listings = listings;
        this.embeddingService = embeddingService;
        this.vectorIndex = vectorIndex;
        this.events = events;
    }

    /**
     * Creates a disclosure for a listing and indexes its text. The listing must exist for the current
     * tenant ({@code 4253} otherwise). Save-then-index: the returned disclosure reflects the post-index
     * state ({@code indexedAt} set on success, null if indexing degraded).
     */
    public Mono<ListingDisclosure> create(UUID listingId, ListingDisclosure disclosure) {
        return TenantContextHolder.required().flatMap(ctx ->
                listings.findByIdAndTenantId(listingId, ctx.tenantId())
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Listing not found for disclosure", 4253, 404)))
                        .flatMap(listing -> {
                            ListingDisclosure toSave = disclosure.toBuilder()
                                    .id(UUID.randomUUID())
                                    .tenantId(ctx.tenantId())
                                    .listingId(listingId)
                                    .indexedAt(null)
                                    .build();
                            return disclosures.save(toSave)
                                    .flatMap(saved -> indexDisclosure(ctx.tenantId(), saved));
                        }));
    }

    /**
     * Updates a disclosure's text/type and re-indexes. {@code 4253} if not found for the tenant.
     */
    public Mono<ListingDisclosure> update(UUID id, ListingDisclosure patch) {
        return TenantContextHolder.required().flatMap(ctx ->
                disclosures.findByIdAndTenantId(id, ctx.tenantId())
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Disclosure not found", 4253, 404)))
                        .flatMap(existing -> {
                            ListingDisclosure updated = existing.toBuilder()
                                    .disclosureType(patch.getDisclosureType() != null
                                            ? patch.getDisclosureType() : existing.getDisclosureType())
                                    .text(patch.getText() != null ? patch.getText() : existing.getText())
                                    .sourceDocAttachmentId(patch.getSourceDocAttachmentId() != null
                                            ? patch.getSourceDocAttachmentId()
                                            : existing.getSourceDocAttachmentId())
                                    .indexedAt(null)
                                    .build();
                            return disclosures.save(updated)
                                    .flatMap(saved -> indexDisclosure(ctx.tenantId(), saved));
                        }));
    }

    public Flux<ListingDisclosure> listForListing(UUID listingId) {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> disclosures.findByTenantIdAndListingId(ctx.tenantId(), listingId));
    }

    /**
     * Embeds the disclosure text and upserts it as source type {@code "ListingDisclosure"} with
     * {@code listingId} metadata; on success stamps {@code indexedAt}, re-saves, and emits
     * {@code LISTING_DISCLOSURE_INDEXED}. Best-effort: a blank text or any embedding/upsert failure logs
     * {@code 4252} and returns the saved-but-unindexed disclosure (never throws, never loses the row).
     */
    private Mono<ListingDisclosure> indexDisclosure(UUID tenantId, ListingDisclosure saved) {
        String text = saved.getText();
        if (text == null || text.isBlank()) {
            log.warn("RE-1: disclosure {} has blank text — skipping indexing (4252)", saved.getId());
            return Mono.just(saved);
        }
        String typeName = saved.getDisclosureType() != null
                ? saved.getDisclosureType().name() : "GENERAL";
        Map<String, Object> meta = new HashMap<>();
        meta.put("listingId", saved.getListingId().toString());
        meta.put("contentPreview", truncate(text, MAX_PREVIEW_CHARS));
        meta.put("title", typeName);
        meta.put("disclosureType", typeName);

        return embeddingService.embed(tenantId, truncate(text, MAX_SNIPPET_CHARS))
                .flatMap(vector -> vectorIndex.upsert(tenantId,
                        RagRetrievalService.LISTING_DISCLOSURE_SOURCE_TYPE, saved.getId(), vector, meta))
                .then(disclosures.save(saved.toBuilder().indexedAt(Instant.now()).build()))
                .doOnNext(indexed -> events.publish(DomainEvent.of(
                        DomainEventType.LISTING_DISCLOSURE_INDEXED, tenantId, indexed.getId(),
                        Map.of("listingId", indexed.getListingId(),
                                "disclosureId", indexed.getId(),
                                "disclosureType", typeName))))
                .onErrorResume(err -> {
                    log.warn("RE-1: disclosure-text indexing failed for {} (best-effort, indexedAt left "
                            + "null, 4252): {}", saved.getId(), err.toString());
                    return Mono.just(saved);
                });
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
