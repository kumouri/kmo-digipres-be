package com.kumouri.kmodigipresbe.module.realestate.controller;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.realestate.RealEstateAutoConfiguration;
import com.kumouri.kmodigipresbe.module.realestate.marketing.ListingMarketingService;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingMarketingDraft;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingPhoto;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Real Estate Concierge (RE-4 — Marketing Studio) — authenticated agent surface for listing-photo intake,
 * one-click marketing generation, and the draft → approve / skip queue.
 *
 * <p>Gating mirrors the {@code ListingController} / {@code NoShowRiskController} precedent:
 * {@code @ConditionalOnProperty(kmosf.modules.realestate.enabled)} (absent from the OpenAPI spec when the
 * module is off) + per-tenant module membership via {@link TenantModuleRegistry#requireEnabled} + STAFF
 * {@link RoleGuard}. Tenant is resolved from the request context.
 *
 * <p><strong>Never auto-publish.</strong> Generation only ever produces a DRAFTED draft; the agent must
 * call {@code approve} (copy-ready, paste-out) — the GBP review-reply draft→approve posture. There is no
 * endpoint that posts to MLS/social (out of scope).
 *
 * <p>The base path {@code /api/v1} is applied by {@code spring.webflux.base-path}, so these map to
 * {@code /api/v1/realestate/listings/{listingId}/marketing...} and
 * {@code /api/v1/realestate/marketing/drafts...}.
 */
@RestController
@ConditionalOnProperty(prefix = "kmosf.modules.realestate", name = "enabled")
@RequiredArgsConstructor
public class ListingMarketingController {

    /** The multipart part name the FE uses for a listing photo. */
    public static final String IMAGE_PART = "image";

    private final ListingMarketingService marketing;
    private final TenantModuleRegistry modules;

    // ── Photo intake ─────────────────────────────────────────────────────────────

    /**
     * Uploads a listing photo as {@code multipart/form-data} (the {@code EquipmentPhotoController}
     * byte-reading pattern, but STAFF-authenticated — agents are logged-in users). The bytes are stored
     * and a {@link ListingPhoto} (+ a generic LISTING {@code Attachment}) is registered for the listing.
     * A missing image part → {@code 4253}/400; an unsupported media type → {@code 4211}/415; a
     * missing/not-owned listing → {@code 4253}/404.
     */
    @PostMapping("/realestate/listings/{listingId}/marketing/photos")
    public Mono<ListingPhoto> uploadPhoto(@PathVariable UUID listingId, ServerWebExchange exchange) {
        return guard().then(exchange.getMultipartData().flatMap(parts -> {
            Part imagePart = parts.getFirst(IMAGE_PART);
            if (!(imagePart instanceof FilePart filePart)) {
                return Mono.error(new DigiPresBeException(
                        "Listing-photo upload is missing its '" + IMAGE_PART + "' image part", 4253, 400));
            }
            String mediaType = filePart.headers().getContentType() == null
                    ? null : filePart.headers().getContentType().toString();
            return readBytes(filePart)
                    .flatMap(bytes -> marketing.addPhoto(listingId, bytes, mediaType, filePart.filename()));
        }));
    }

    /** Lists the listing's uploaded photos (oldest-first). */
    @GetMapping("/realestate/listings/{listingId}/marketing/photos")
    public Flux<ListingPhoto> listPhotos(@PathVariable UUID listingId) {
        return guard().thenMany(marketing.listPhotos(listingId));
    }

    // ── Generate (→ DRAFTED) ─────────────────────────────────────────────────────

    /**
     * One-click generate: vision-captions the listing photos + drafts MLS remarks / social captions /
     * email blast (Sonnet) + runs the Fair-Housing lint, persisting a DRAFTED {@link ListingMarketingDraft}
     * (never auto-published). Best-effort: a Claude/vision failure yields a partial/empty DRAFTED draft +
     * a degraded flag, never an error. A missing/not-owned listing → {@code 4253}/404.
     */
    @PostMapping("/realestate/listings/{listingId}/marketing/generate")
    public Mono<ListingMarketingDraft> generate(@PathVariable UUID listingId) {
        return guard().then(marketing.generate(listingId));
    }

    /** Lists a listing's marketing drafts (history), most-recent first. */
    @GetMapping("/realestate/listings/{listingId}/marketing/drafts")
    public Flux<ListingMarketingDraft> listForListing(@PathVariable UUID listingId) {
        return guard().thenMany(marketing.listForListing(listingId));
    }

    // ── Draft queue: list / approve / skip ───────────────────────────────────────

    /** The tenant's DRAFTED marketing drafts (the review queue), most-recent first. */
    @GetMapping("/realestate/marketing/drafts")
    public Flux<ListingMarketingDraft> listDrafted() {
        return guard().thenMany(marketing.listDrafted());
    }

    /**
     * Approves a DRAFTED draft → APPROVED (copy-ready, paste-out — the GBP approve→post posture; the
     * actual MLS/social posting is out of scope). {@code 4253} not-found; {@code 4253}/409 if not DRAFTED.
     */
    @PostMapping("/realestate/marketing/drafts/{id}/approve")
    public Mono<ListingMarketingDraft> approve(@PathVariable UUID id) {
        return guard().then(marketing.approve(id));
    }

    /** Skips a DRAFTED draft (discard) → SKIPPED. {@code 4253} not-found; {@code 4253}/409 if not DRAFTED. */
    @PostMapping("/realestate/marketing/drafts/{id}/skip")
    public Mono<ListingMarketingDraft> skip(@PathVariable UUID id) {
        return guard().then(marketing.skip(id));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Joins a {@link FilePart}'s content into a single {@code byte[]} (releases the buffer). */
    private static Mono<byte[]> readBytes(FilePart filePart) {
        return DataBufferUtils.join(filePart.content())
                .map(ListingMarketingController::toByteArray);
    }

    private static byte[] toByteArray(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(RealEstateAutoConfiguration.MODULE_KEY)
                .then(RoleGuard.requireRole("STAFF"));
    }
}
