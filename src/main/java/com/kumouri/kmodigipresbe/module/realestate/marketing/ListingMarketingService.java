package com.kumouri.kmodigipresbe.module.realestate.marketing;

import com.fasterxml.jackson.databind.JsonNode;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.files.Attachment;
import com.kumouri.kmodigipresbe.module.realestate.model.Listing;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingMarketingDraft;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingMarketingDraft.FairHousingFlag;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingMarketingDraft.GeneratedPiece;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingMarketingDraft.PhotoCaption;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingMarketingDraftRepository;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingPhoto;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingPhotoRepository;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingRepository;
import com.kumouri.kmodigipresbe.module.realestate.model.MarketingChannel;
import com.kumouri.kmodigipresbe.repository.AttachmentRepository;
import com.kumouri.kmodigipresbe.service.ai.vision.AiVisionService;
import com.kumouri.kmodigipresbe.service.storage.FileStorageService;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real Estate Concierge (RE-4 — Marketing Studio) — the orchestrator: <strong>photo intake</strong>,
 * <strong>generate</strong> (vision-caption each listing photo + Sonnet draft of MLS remarks / social
 * captions / email blast + the Fair-Housing lint), and the <strong>draft → approve / skip</strong> queue
 * (the GBP {@code GbpReviewReplyAdminService} posture — <strong>never auto-published</strong>).
 *
 * <h2>Photo intake</h2>
 * {@link #addPhoto} stores the bytes via the shared {@code FileStorageService.putBytes} (the
 * {@code EquipmentVisionService} precedent) under the {@code listing-photos} partition, registers a
 * generic {@code Attachment(subjectType="LISTING")} so the photo appears on the standard attachment
 * surface, and persists a {@link ListingPhoto} linking the two. The listing must exist for the tenant
 * ({@code 4253}).
 *
 * <h2>Generate (RE-4 §5 decision 5)</h2>
 * {@link #generate}: load the listing ({@code 4253}); for each listing photo read its bytes back
 * ({@code FileStorageService.getBytes}) and run the UNCHANGED {@code AiVisionService.extract} for a
 * {@code {caption, features[]}} feature read (best-effort — a failed/blank photo degrades to no caption,
 * never throws, {@code 4266} when there are no photos at all); then call {@code MarketingGenerationService}
 * (Sonnet) for the per-channel copy grounded in the listing facts + the photo notes; then run the
 * deterministic {@code FairHousingLint} over every generated piece (layer 2 of the guardrail). The whole
 * package persists as a {@code DRAFTED} {@link ListingMarketingDraft} (never auto-published) and
 * {@code LISTING_MARKETING_DRAFTED} is emitted. <strong>Best-effort:</strong> a Claude budget/upstream/
 * parse failure yields a partial/empty package + {@code generationDegraded=true} ({@code 4268}) — the
 * draft is still saved DRAFTED, never an error.
 *
 * <h2>Approve / skip</h2>
 * {@link #approve} flips a {@code DRAFTED} draft to {@code APPROVED} (copy-ready — paste-out; the actual
 * MLS/social posting is out of scope, the GBP approve→post posture) and emits
 * {@code LISTING_MARKETING_APPROVED}; {@link #skip} discards it ({@code SKIPPED}). Both reject a
 * non-{@code DRAFTED} row with an explicit status check ({@code 4253} not-found, the same-status-guard
 * {@code GbpReviewReplyAdminService} {@code 4033} posture reused as a 409 here).
 *
 * <p>Hand-constructed as a {@code @Bean} by {@code RealEstateAutoConfiguration} (no {@code @Service}
 * annotation) so it exists only when the module is enabled — blast-radius zero.
 */
@Slf4j
public class ListingMarketingService {

    /** The S3 partition (under the tenant root) listing photos are stored in. */
    static final String STORAGE_PARTITION = "listing-photos";

    private static final String VISION_SYSTEM_PROMPT =
            "You caption a real-estate listing photo for marketing. Respond with ONLY a single minified "
            + "JSON object and nothing else — no prose, no markdown, no code fences. The object MUST have "
            + "exactly these keys: \"caption\" (one short marketing-friendly sentence describing what the "
            + "photo shows, or null if you cannot tell) and \"features\" (a JSON array of short feature "
            + "strings visible in the photo, e.g. [\"granite countertops\", \"stainless appliances\", "
            + "\"hardwood floors\"], or [] if none). Describe only the PROPERTY and its visible features. "
            + "Do NOT describe or infer the kind of person who lives there; no protected-class or steering "
            + "language. Do not invent features you cannot see.";

    private static final String VISION_USER_TEXT =
            "Caption this listing photo and list its visible features as the specified JSON.";

    private final ListingRepository listings;
    private final ListingPhotoRepository photos;
    private final ListingMarketingDraftRepository drafts;
    private final AttachmentRepository attachments;
    private final FileStorageService storage;
    private final AiVisionService visionService;
    private final MarketingGenerationService generationService;
    private final DomainEventPublisher events;
    private final String visionModel;

    public ListingMarketingService(ListingRepository listings,
                                   ListingPhotoRepository photos,
                                   ListingMarketingDraftRepository drafts,
                                   AttachmentRepository attachments,
                                   FileStorageService storage,
                                   AiVisionService visionService,
                                   MarketingGenerationService generationService,
                                   DomainEventPublisher events,
                                   String visionModel) {
        this.listings = listings;
        this.photos = photos;
        this.drafts = drafts;
        this.attachments = attachments;
        this.storage = storage;
        this.visionService = visionService;
        this.generationService = generationService;
        this.events = events;
        this.visionModel = visionModel;
    }

    // ── Photo intake ─────────────────────────────────────────────────────────────

    /**
     * Stores a listing photo (bytes → S3 + a generic {@code Attachment(subjectType="LISTING")} + a
     * {@link ListingPhoto}). The listing must exist for the current tenant ({@code 4253}); the media type
     * must be a vision-supported image ({@code 4211}, the shared {@code AiVisionService} gate reused). The
     * bytes are stored unmodified.
     */
    public Mono<ListingPhoto> addPhoto(UUID listingId, byte[] imageBytes, String mediaType,
                                       String filename) {
        if (imageBytes == null || imageBytes.length == 0) {
            return Mono.error(new DigiPresBeException(
                    "Listing-photo upload is missing its image bytes", 4253, 400));
        }
        if (!AiVisionService.isSupportedMediaType(mediaType)) {
            return Mono.error(new DigiPresBeException(
                    "Unsupported image media type '" + mediaType
                            + "' (accepted: image/jpeg, image/png, image/webp, image/gif)", 4211, 415));
        }
        return TenantContextHolder.required().flatMap(ctx ->
                listings.findByIdAndTenantId(listingId, ctx.tenantId())
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Listing not found for photo upload", 4253, 404)))
                        .flatMap(listing -> {
                            String suffix = suffixFor(mediaType, filename);
                            return storage.putBytes(ctx.tenantId(), STORAGE_PARTITION, imageBytes,
                                            mediaType, suffix)
                                    .flatMap(storageRef -> attachments.save(Attachment.builder()
                                                    .tenantId(ctx.tenantId())
                                                    .subjectType(ListingPhoto.SUBJECT_TYPE)
                                                    .subjectId(listingId)
                                                    .filename(filename != null && !filename.isBlank()
                                                            ? filename : "listing-photo." + suffix)
                                                    .contentType(mediaType)
                                                    .sizeBytes((long) imageBytes.length)
                                                    .storageRef(storageRef)
                                                    .uploadedByUserId(ctx.userId())
                                                    .build())
                                            .flatMap(att -> photos.save(ListingPhoto.builder()
                                                    .id(UUID.randomUUID())
                                                    .tenantId(ctx.tenantId())
                                                    .listingId(listingId)
                                                    .attachmentId(att.getId())
                                                    .storageRef(storageRef)
                                                    .filename(att.getFilename())
                                                    .contentType(mediaType)
                                                    .sizeBytes((long) imageBytes.length)
                                                    .build())));
                        }));
    }

    /** Lists a listing's photos (the console surface), oldest-first. */
    public Flux<ListingPhoto> listPhotos(UUID listingId) {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> photos.findByTenantIdAndListingIdOrderByCreatedAtAsc(
                        ctx.tenantId(), listingId));
    }

    // ── Generate (vision captions + Sonnet copy + Fair-Housing lint → DRAFTED) ────

    /**
     * Generates a marketing package for the listing and persists it DRAFTED (never auto-published). The
     * listing must exist for the current tenant ({@code 4253}). Captioning + generation are best-effort:
     * a photo whose vision read fails contributes no caption; a Claude failure yields a partial/empty
     * package + {@code generationDegraded=true} ({@code 4268}). {@code 4266} (no photos to caption) is an
     * advisory log — text-only generation still proceeds.
     */
    public Mono<ListingMarketingDraft> generate(UUID listingId) {
        return TenantContextHolder.required().flatMap(ctx ->
                listings.findByIdAndTenantId(listingId, ctx.tenantId())
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Listing not found for marketing generation", 4253, 404)))
                        .flatMap(listing -> captionPhotos(ctx.tenantId(), listingId)
                                .flatMap(captions -> draftAndPersist(ctx.tenantId(), listing, captions))));
    }

    /**
     * Reads each listing photo's bytes back and runs {@code AiVisionService.extract} for a feature read.
     * Best-effort per photo: a getBytes / vision failure (or a blank read) contributes no caption — the
     * stream never errors. Returns the (possibly empty) caption list; an empty photo set logs {@code 4266}.
     */
    private Mono<List<PhotoCaption>> captionPhotos(UUID tenantId, UUID listingId) {
        return photos.findByTenantIdAndListingIdOrderByCreatedAtAsc(tenantId, listingId)
                .concatMap(photo -> captionOne(tenantId, photo))
                .collectList()
                .map(captions -> {
                    List<PhotoCaption> nonEmpty = new ArrayList<>();
                    for (PhotoCaption c : captions) {
                        if (c != null) {
                            nonEmpty.add(c);
                        }
                    }
                    if (nonEmpty.isEmpty()) {
                        log.info("RE-4: no usable listing-photo captions for listing {} "
                                + "(no photos or all reads degraded, 4266) — text-only generation", listingId);
                    }
                    return nonEmpty;
                });
    }

    /**
     * Captions one photo: read bytes ({@code getBytes}) → {@code AiVisionService.extract} → parse the
     * {@code {caption, features[]}} JSON. Any failure (read, budget {@code 1200}, upstream {@code 1202},
     * parse) degrades to a no-caption ({@code Mono.empty()} → dropped by {@code concatMap}) — best-effort,
     * never drops the whole generation.
     */
    private Mono<PhotoCaption> captionOne(UUID tenantId, ListingPhoto photo) {
        return storage.getBytes(tenantId, photo.getStorageRef())
                .flatMap(bytes -> visionService.extract(
                        bytes, photo.getContentType(), visionModel, VISION_SYSTEM_PROMPT, VISION_USER_TEXT))
                .map(json -> toCaption(photo.getId(), json))
                .filter(c -> c.getCaption() != null || !c.getFeatures().isEmpty())
                .onErrorResume(e -> {
                    log.warn("RE-4: listing-photo caption failed for {} (best-effort, no caption): {}",
                            photo.getId(), e.toString());
                    return Mono.empty();
                });
    }

    /** Maps the vision {@code extract} JSON to a {@link PhotoCaption} (blank caption + empty features ok). */
    private static PhotoCaption toCaption(UUID photoId, JsonNode json) {
        String caption = null;
        JsonNode capNode = json.path("caption");
        if (!capNode.isMissingNode() && !capNode.isNull()) {
            String s = capNode.asText(null);
            if (s != null && !s.isBlank()) {
                caption = s.trim();
            }
        }
        List<String> features = new ArrayList<>();
        JsonNode featNode = json.path("features");
        if (featNode.isArray()) {
            for (JsonNode f : featNode) {
                String s = f.asText(null);
                if (s != null && !s.isBlank()) {
                    features.add(s.trim());
                }
            }
        }
        return PhotoCaption.builder().photoId(photoId).caption(caption).features(features).build();
    }

    /**
     * Calls the Sonnet generator (grounded in the listing facts + the photo notes), runs the Fair-Housing
     * lint over the result, and persists the DRAFTED draft + emits the event. A generation failure
     * (budget/upstream/missing-key) is swallowed → an empty package + {@code generationDegraded=true}
     * ({@code 4268}); the {@code generate} returning an empty map also yields a degraded (empty) draft.
     */
    private Mono<ListingMarketingDraft> draftAndPersist(UUID tenantId, Listing listing,
                                                        List<PhotoCaption> captions) {
        String listingFacts = buildListingFacts(listing);
        String photoNotes = buildPhotoNotes(captions);
        return generationService.generate(listingFacts, photoNotes)
                .onErrorResume(e -> {
                    log.warn("RE-4: marketing generation failed for listing {} (best-effort, degraded "
                            + "draft, 4268): {}", listing.getId(), e.toString());
                    return Mono.just(Map.<MarketingChannel, String>of());
                })
                .flatMap(pieceMap -> persistDraft(tenantId, listing, captions, pieceMap));
    }

    private Mono<ListingMarketingDraft> persistDraft(UUID tenantId, Listing listing,
                                                     List<PhotoCaption> captions,
                                                     Map<MarketingChannel, String> pieceMap) {
        boolean degraded = pieceMap.isEmpty();
        List<GeneratedPiece> pieces = new ArrayList<>();
        List<FairHousingFlag> flags = new ArrayList<>();
        // Deterministic channel order for a stable draft + lint.
        for (MarketingChannel channel : MarketingChannel.values()) {
            String text = pieceMap.get(channel);
            if (text == null || text.isBlank()) {
                continue;
            }
            pieces.add(GeneratedPiece.builder().channel(channel).text(text).build());
            flags.addAll(FairHousingLint.lint(channel, text));
        }
        ListingMarketingDraft draft = ListingMarketingDraft.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .listingId(listing.getId())
                .pieces(pieces)
                .photoCaptions(captions)
                .fairHousingFlags(flags)
                .fairHousingFlagged(!flags.isEmpty())
                .generationDegraded(degraded)
                .status(ListingMarketingDraft.Status.DRAFTED)
                .build();
        return drafts.save(draft).doOnNext(saved -> emitDrafted(tenantId, saved));
    }

    // ── Draft queue: list / approve / skip (never auto-publish) ──────────────────

    /** The tenant's DRAFTED marketing drafts (the agent's review queue), most-recent first. */
    public Flux<ListingMarketingDraft> listDrafted() {
        return TenantContextHolder.required().flatMapMany(ctx ->
                drafts.findByTenantIdAndStatusOrderByCreatedAtDesc(
                        ctx.tenantId(), ListingMarketingDraft.Status.DRAFTED));
    }

    /** All marketing drafts for one listing (the listing's history), most-recent first. */
    public Flux<ListingMarketingDraft> listForListing(UUID listingId) {
        return TenantContextHolder.required().flatMapMany(ctx ->
                drafts.findByTenantIdAndListingIdOrderByCreatedAtDesc(ctx.tenantId(), listingId));
    }

    /**
     * Approves a {@code DRAFTED} draft → {@code APPROVED} (copy-ready; paste-out — the actual posting is
     * out of scope, the GBP approve→post posture) and emits {@code LISTING_MARKETING_APPROVED}.
     * {@code 4253} if no such draft for the tenant; {@code 4253}/409 if it is not {@code DRAFTED}
     * (the {@code GbpReviewReplyAdminService} {@code 4033} same-status guard, RE-local code).
     */
    public Mono<ListingMarketingDraft> approve(UUID id) {
        return TenantContextHolder.required().flatMap(ctx ->
                drafts.findByTenantIdAndId(ctx.tenantId(), id)
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Marketing draft not found", 4253, 404)))
                        .flatMap(draft -> {
                            if (draft.getStatus() != ListingMarketingDraft.Status.DRAFTED) {
                                return Mono.error(new DigiPresBeException(
                                        "Marketing draft is not DRAFTED (status=" + draft.getStatus()
                                                + ") — cannot approve", 4253, 409));
                            }
                            draft.setStatus(ListingMarketingDraft.Status.APPROVED);
                            draft.setApprovedAt(Instant.now());
                            draft.setApprovedByUserId(ctx.userId());
                            return drafts.save(draft)
                                    .doOnNext(saved -> emitApproved(ctx.tenantId(), saved));
                        }));
    }

    /**
     * Skips a {@code DRAFTED} draft (the agent chose not to use it) → {@code SKIPPED}. {@code 4253} if no
     * such draft; {@code 4253}/409 if it is not {@code DRAFTED}.
     */
    public Mono<ListingMarketingDraft> skip(UUID id) {
        return TenantContextHolder.required().flatMap(ctx ->
                drafts.findByTenantIdAndId(ctx.tenantId(), id)
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Marketing draft not found", 4253, 404)))
                        .flatMap(draft -> {
                            if (draft.getStatus() != ListingMarketingDraft.Status.DRAFTED) {
                                return Mono.error(new DigiPresBeException(
                                        "Marketing draft is not DRAFTED (status=" + draft.getStatus()
                                                + ") — cannot skip", 4253, 409));
                            }
                            draft.setStatus(ListingMarketingDraft.Status.SKIPPED);
                            return drafts.save(draft);
                        }));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static String buildListingFacts(Listing l) {
        StringBuilder sb = new StringBuilder();
        sb.append("Address: ").append(join(l.getAddressLine(), l.getCity(), l.getState(), l.getZip()))
                .append('\n');
        if (l.getPrice() != null) {
            sb.append("Price: $").append(l.getPrice().toPlainString()).append('\n');
        }
        if (l.getBeds() != null) {
            sb.append("Beds: ").append(l.getBeds()).append('\n');
        }
        if (l.getBaths() != null) {
            sb.append("Baths: ").append(l.getBaths().toPlainString()).append('\n');
        }
        if (l.getSqft() != null) {
            sb.append("Square feet: ").append(l.getSqft()).append('\n');
        }
        if (l.getStatus() != null) {
            sb.append("Status: ").append(l.getStatus().name()).append('\n');
        }
        return sb.toString().trim();
    }

    private static String buildPhotoNotes(List<PhotoCaption> captions) {
        if (captions == null || captions.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (PhotoCaption c : captions) {
            sb.append("Photo ").append(i++).append(": ");
            if (c.getCaption() != null && !c.getCaption().isBlank()) {
                sb.append(c.getCaption());
            }
            if (c.getFeatures() != null && !c.getFeatures().isEmpty()) {
                sb.append(" [features: ").append(String.join(", ", c.getFeatures())).append("]");
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(p.trim());
            }
        }
        return sb.toString();
    }

    private void emitDrafted(UUID tenantId, ListingMarketingDraft draft) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("draftId", draft.getId().toString());
        payload.put("listingId", draft.getListingId().toString());
        payload.put("channelCount", draft.getPieces() == null ? 0 : draft.getPieces().size());
        payload.put("photoCaptionCount", draft.getPhotoCaptions() == null
                ? 0 : draft.getPhotoCaptions().size());
        payload.put("fairHousingFlagCount", draft.getFairHousingFlags() == null
                ? 0 : draft.getFairHousingFlags().size());
        payload.put("generationDegraded", draft.isGenerationDegraded());
        events.publish(DomainEvent.of(
                DomainEventType.LISTING_MARKETING_DRAFTED, tenantId, draft.getId(), payload));
    }

    private void emitApproved(UUID tenantId, ListingMarketingDraft draft) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("draftId", draft.getId().toString());
        payload.put("listingId", draft.getListingId().toString());
        events.publish(DomainEvent.of(
                DomainEventType.LISTING_MARKETING_APPROVED, tenantId, draft.getId(), payload));
    }

    private static String suffixFor(String mediaType, String filename) {
        if (filename != null && filename.contains(".")) {
            String ext = filename.substring(filename.lastIndexOf('.') + 1).trim();
            if (!ext.isEmpty() && ext.length() <= 5) {
                return ext.toLowerCase();
            }
        }
        if (mediaType == null) {
            return "jpg";
        }
        return switch (mediaType.trim().toLowerCase()) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "jpg";
        };
    }
}
