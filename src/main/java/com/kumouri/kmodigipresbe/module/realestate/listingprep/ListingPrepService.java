package com.kumouri.kmodigipresbe.module.realestate.listingprep;

import com.fasterxml.jackson.databind.JsonNode;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.realestate.listingprep.SocialCalendarGenerationService.CalendarPost;
import com.kumouri.kmodigipresbe.module.realestate.listingprep.model.ListingPrepPack;
import com.kumouri.kmodigipresbe.module.realestate.listingprep.model.ListingPrepPack.FairHousingFlag;
import com.kumouri.kmodigipresbe.module.realestate.listingprep.model.ListingPrepPack.PhotoNote;
import com.kumouri.kmodigipresbe.module.realestate.listingprep.model.ListingPrepPack.SocialPost;
import com.kumouri.kmodigipresbe.module.realestate.listingprep.model.ListingPrepPackRepository;
import com.kumouri.kmodigipresbe.module.realestate.marketing.FairHousingLint;
import com.kumouri.kmodigipresbe.module.realestate.marketing.MarketingGenerationService;
import com.kumouri.kmodigipresbe.module.realestate.model.Listing;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingPhoto;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingPhotoRepository;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingRepository;
import com.kumouri.kmodigipresbe.module.realestate.model.MarketingChannel;
import com.kumouri.kmodigipresbe.service.ai.vision.AiVisionService;
import com.kumouri.kmodigipresbe.service.storage.FileStorageService;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real Estate Concierge (T10 — Listing Prep Studio) — the orchestrator that packages a cohesive
 * <strong>listing prep pack</strong>: an MLS description + a <strong>4-week dated social calendar</strong> +
 * an email campaign, all Fair-Housing-safe, parked in a draft → approve queue (the RE-4
 * {@code ListingMarketingService} posture — <strong>never auto-published</strong>).
 *
 * <h2>Maximal reuse of RE-4 (those cores stay byte-unchanged)</h2>
 * <ul>
 *   <li><strong>Vision feature/condition extraction</strong> — reuses the UNCHANGED
 *       {@code AiVisionService.extract} exactly as the RE-4 {@code ListingMarketingService} does (the same
 *       prompt + the same {@code {caption, features[]}} read; best-effort per photo). T10 does not rebuild
 *       this; it persists the callouts onto the pack.</li>
 *   <li><strong>MLS description + email</strong> — reuses the UNCHANGED {@code MarketingGenerationService}
 *       (its result map already carries {@code MLS_REMARKS} + {@code EMAIL_BLAST}); T10 takes those two and
 *       ignores the ad-hoc social captions (the calendar supersedes them).</li>
 *   <li><strong>Fair-Housing lint</strong> — reuses the UNCHANGED {@code FairHousingLint} over the
 *       description, every calendar post, and the email.</li>
 * </ul>
 * The only net-new generation is the {@link SocialCalendarGenerationService} (the 4-week calendar). The small
 * facts/notes string builders are replicated locally (pure helpers) so RE-4's private surface is not widened.
 *
 * <h2>Generate ({@link #generate})</h2>
 * load the listing ({@code 4253}) → caption photos (reused vision, best-effort, {@code 4462} when there are
 * none) → in one composition: {@code MarketingGenerationService.generate} for the description+email
 * (best-effort → degraded {@code 4463}) + {@code SocialCalendarGenerationService.generate} for the calendar
 * (best-effort → empty) → assign real {@link LocalDate}s from the start date (default: the next Monday) →
 * <strong>lint</strong> the description + every post + the email. <strong>Fair-Housing hard rule on the
 * calendar:</strong> a flagged post is held + its copy replaced with {@link #SAFE_POST_SUBSTITUTE}
 * ({@code fairHousingSafe=false}, {@code calendarHeldCount++}) — a non-compliant post is <strong>never
 * emitted</strong>. A flagged description / email is surfaced on the rollup (the agent reviews — the RE-4
 * posture). Persist a DRAFTED {@link ListingPrepPack} + emit {@code LISTING_PREP_GENERATED}.
 *
 * <h2>Approve / skip</h2>
 * {@link #approve} flips a {@code DRAFTED} pack to {@code APPROVED} (copy-ready paste-out) + emits
 * {@code LISTING_PREP_APPROVED}; {@link #skip} discards it ({@code SKIPPED}). Both reject a non-{@code DRAFTED}
 * pack with an explicit status check ({@code 4460} not-found, {@code 4461}/409 not-DRAFTED — the RE-4
 * same-status guard). <strong>No {@code switchIfEmpty(create)} anywhere</strong> — the only {@code switchIfEmpty}
 * are genuine not-found ({@code 4253}/{@code 4460}).
 *
 * <p>Hand-constructed as a {@code @Bean} by {@code RealEstateAutoConfiguration} (no {@code @Service}
 * annotation) so it exists only when the module is enabled — blast-radius zero.
 */
@Slf4j
public class ListingPrepService {

    /** The vetted neutral substitute for a calendar post whose generated copy the lint flagged. */
    static final String SAFE_POST_SUBSTITUTE =
            "Now available — come see this property for yourself. Contact us today to schedule a tour.";

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
    private final ListingPrepPackRepository packs;
    private final FileStorageService storage;
    private final AiVisionService visionService;
    private final MarketingGenerationService marketingGeneration;
    private final SocialCalendarGenerationService calendarGeneration;
    private final DomainEventPublisher events;
    private final String visionModel;
    private final int postsPerWeek;

    public ListingPrepService(ListingRepository listings,
                              ListingPhotoRepository photos,
                              ListingPrepPackRepository packs,
                              FileStorageService storage,
                              AiVisionService visionService,
                              MarketingGenerationService marketingGeneration,
                              SocialCalendarGenerationService calendarGeneration,
                              DomainEventPublisher events,
                              String visionModel,
                              int postsPerWeek) {
        this.listings = listings;
        this.photos = photos;
        this.packs = packs;
        this.storage = storage;
        this.visionService = visionService;
        this.marketingGeneration = marketingGeneration;
        this.calendarGeneration = calendarGeneration;
        this.events = events;
        this.visionModel = visionModel;
        this.postsPerWeek = postsPerWeek;
    }

    // ── Generate (reuse RE-4 description+email+vision; add the 4-week calendar; lint all → DRAFTED) ──

    /**
     * Generates a prep pack for the listing and persists it DRAFTED (never auto-published). The listing must
     * exist for the current tenant ({@code 4253}). Captioning + generation are best-effort: a photo whose
     * vision read fails contributes no caption; a Claude failure on the description/email yields a degraded
     * pack ({@code 4463}); a Claude failure on the calendar yields an empty calendar. A flagged calendar post
     * is held + safe-substituted (never emitted).
     *
     * @param startDate     the calendar's day-zero (nullable → the next Monday)
     * @param postsPerWeekOverride per-call posts-per-week (nullable → the configured default)
     */
    public Mono<ListingPrepPack> generate(UUID listingId, LocalDate startDate, Integer postsPerWeekOverride) {
        int perWeek = postsPerWeekOverride != null && postsPerWeekOverride > 0
                ? postsPerWeekOverride : postsPerWeek;
        LocalDate effectiveStart = startDate != null ? startDate : nextMonday();
        return TenantContextHolder.required().flatMap(ctx ->
                listings.findByIdAndTenantId(listingId, ctx.tenantId())
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Listing not found for prep generation", 4253, 404)))
                        .flatMap(listing -> captionPhotos(ctx.tenantId(), listingId)
                                .flatMap(notes -> draftAndPersist(
                                        ctx.tenantId(), listing, notes, effectiveStart, perWeek))));
    }

    /**
     * Reads each listing photo's bytes back and runs {@code AiVisionService.extract} for a feature read
     * (the reused RE-4 vision path). Best-effort per photo: a getBytes / vision failure (or a blank read)
     * contributes no note — the stream never errors. An empty photo set logs {@code 4462}.
     */
    private Mono<List<PhotoNote>> captionPhotos(UUID tenantId, UUID listingId) {
        return photos.findByTenantIdAndListingIdOrderByCreatedAtAsc(tenantId, listingId)
                .concatMap(photo -> captionOne(tenantId, photo))
                .collectList()
                .map(notes -> {
                    List<PhotoNote> nonEmpty = new ArrayList<>();
                    for (PhotoNote n : notes) {
                        if (n != null) {
                            nonEmpty.add(n);
                        }
                    }
                    if (nonEmpty.isEmpty()) {
                        log.info("T10: no usable listing-photo captions for listing {} (no photos or all "
                                + "reads degraded, 4462) — text-only prep generation", listingId);
                    }
                    return nonEmpty;
                });
    }

    /**
     * Captions one photo: read bytes ({@code getBytes}) → {@code AiVisionService.extract} → parse the
     * {@code {caption, features[]}} JSON. Any failure (read, budget {@code 1200}, upstream {@code 1202},
     * parse) degrades to no note ({@code Mono.empty()} → dropped) — best-effort, never drops the whole
     * generation.
     */
    private Mono<PhotoNote> captionOne(UUID tenantId, ListingPhoto photo) {
        return storage.getBytes(tenantId, photo.getStorageRef())
                .flatMap(bytes -> visionService.extract(
                        bytes, photo.getContentType(), visionModel, VISION_SYSTEM_PROMPT, VISION_USER_TEXT))
                .map(json -> toNote(photo.getId(), json))
                .filter(n -> n.getCaption() != null || !n.getFeatures().isEmpty())
                .onErrorResume(e -> {
                    log.warn("T10: listing-photo caption failed for {} (best-effort, no note): {}",
                            photo.getId(), e.toString());
                    return Mono.empty();
                });
    }

    /** Maps the vision {@code extract} JSON to a {@link PhotoNote} (blank caption + empty features ok). */
    private static PhotoNote toNote(UUID photoId, JsonNode json) {
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
        return PhotoNote.builder().photoId(photoId).caption(caption).features(features).build();
    }

    /**
     * Runs the reused RE-4 generator (description + email) and the new calendar generator (both best-effort),
     * assigns dates, lints everything, and persists the DRAFTED pack + emits the event.
     */
    private Mono<ListingPrepPack> draftAndPersist(UUID tenantId, Listing listing, List<PhotoNote> notes,
                                                  LocalDate startDate, int perWeek) {
        String listingFacts = buildListingFacts(listing);
        String photoNotes = buildPhotoNotes(notes);

        Mono<Map<MarketingChannel, String>> marketingMono = marketingGeneration
                .generate(listingFacts, photoNotes)
                .onErrorResume(e -> {
                    log.warn("T10: description/email generation failed for listing {} (best-effort, "
                            + "degraded, 4463): {}", listing.getId(), e.toString());
                    return Mono.just(Map.of());
                });
        Mono<List<CalendarPost>> calendarMono = calendarGeneration
                .generate(listingFacts, photoNotes, perWeek)
                .onErrorResume(e -> {
                    log.warn("T10: social-calendar generation failed for listing {} (best-effort, empty "
                            + "calendar, 4463): {}", listing.getId(), e.toString());
                    return Mono.just(List.of());
                });

        return Mono.zip(marketingMono, calendarMono)
                .flatMap(tuple -> persistPack(
                        tenantId, listing, notes, tuple.getT1(), tuple.getT2(), startDate));
    }

    private Mono<ListingPrepPack> persistPack(UUID tenantId, Listing listing, List<PhotoNote> notes,
                                              Map<MarketingChannel, String> marketing,
                                              List<CalendarPost> rawCalendar, LocalDate startDate) {
        String description = marketing.get(MarketingChannel.MLS_REMARKS);
        String email = marketing.get(MarketingChannel.EMAIL_BLAST);

        List<FairHousingFlag> flags = new ArrayList<>();
        // Layer-2 lint over the reused RE-4 description + email (flagged → surfaced for the agent, RE-4 posture).
        addFlags(flags, "DESCRIPTION", FairHousingLint.lint(MarketingChannel.MLS_REMARKS, description));
        addFlags(flags, "EMAIL", FairHousingLint.lint(MarketingChannel.EMAIL_BLAST, email));

        // The 4-week calendar — date-assign + lint each post; a flagged post is HELD + safe-substituted.
        List<SocialPost> calendar = new ArrayList<>();
        int held = 0;
        for (CalendarPost cp : rawCalendar) {
            LocalDate postDate = startDate.plusDays(cp.dayOffset());
            List<ListingMarketingFlag> postFlags = lintPost(cp);
            if (!postFlags.isEmpty()) {
                held++;
                String reason = postFlags.get(0).term();
                addFlagsRaw(flags, "CALENDAR week " + cp.week(), postFlags);
                calendar.add(SocialPost.builder()
                        .postDate(postDate).weekIndex(cp.week()).dayOffset(cp.dayOffset())
                        .channel(cp.channel())
                        .copy(SAFE_POST_SUBSTITUTE)
                        .fairHousingSafe(false)
                        .heldReason(reason)
                        .build());
            } else {
                calendar.add(SocialPost.builder()
                        .postDate(postDate).weekIndex(cp.week()).dayOffset(cp.dayOffset())
                        .channel(cp.channel())
                        .copy(cp.copy())
                        .fairHousingSafe(true)
                        .build());
            }
        }
        calendar.sort((a, b) -> {
            int byDate = a.getPostDate().compareTo(b.getPostDate());
            return byDate != 0 ? byDate : a.getChannel().compareTo(b.getChannel());
        });

        // Degraded iff BOTH AI legs produced nothing (no description+email AND no calendar).
        boolean degraded = (description == null || description.isBlank())
                && (email == null || email.isBlank())
                && calendar.isEmpty();

        ListingPrepPack pack = ListingPrepPack.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .listingId(listing.getId())
                .mlsDescription(description)
                .emailCampaign(email)
                .socialCalendar(calendar)
                .photoCaptions(notes)
                .fairHousingFlags(flags)
                .fairHousingFlagged(!flags.isEmpty())
                .calendarHeldCount(held)
                .generationDegraded(degraded)
                .status(ListingPrepPack.Status.DRAFTED)
                .build();
        return packs.save(pack).doOnNext(saved -> emitGenerated(tenantId, saved));
    }

    /** Lints one calendar post's copy, returning the matched terms (empty = clean). */
    private static List<ListingMarketingFlag> lintPost(CalendarPost cp) {
        List<ListingMarketingFlag> out = new ArrayList<>();
        for (var f : FairHousingLint.lint(cp.channel(), cp.copy())) {
            out.add(new ListingMarketingFlag(f.getTerm(), f.getSnippet()));
        }
        return out;
    }

    private static void addFlags(List<FairHousingFlag> sink, String surface,
                                 List<com.kumouri.kmodigipresbe.module.realestate.model.ListingMarketingDraft.FairHousingFlag> raw) {
        for (var f : raw) {
            sink.add(FairHousingFlag.builder()
                    .term(f.getTerm()).surface(surface).snippet(f.getSnippet()).build());
        }
    }

    private static void addFlagsRaw(List<FairHousingFlag> sink, String surface,
                                    List<ListingMarketingFlag> raw) {
        for (ListingMarketingFlag f : raw) {
            sink.add(FairHousingFlag.builder()
                    .term(f.term()).surface(surface).snippet(f.snippet()).build());
        }
    }

    // ── Queue: list / get / approve / skip (never auto-publish) ──────────────────

    /** The tenant's DRAFTED prep packs (the agent's review queue), most-recent first. */
    public Flux<ListingPrepPack> listDrafted() {
        return TenantContextHolder.required().flatMapMany(ctx ->
                packs.findByTenantIdAndStatusOrderByCreatedAtDesc(
                        ctx.tenantId(), ListingPrepPack.Status.DRAFTED));
    }

    /** All prep packs for one listing (history), most-recent first. */
    public Flux<ListingPrepPack> listForListing(UUID listingId) {
        return TenantContextHolder.required().flatMapMany(ctx ->
                packs.findByTenantIdAndListingIdOrderByCreatedAtDesc(ctx.tenantId(), listingId));
    }

    /** A single prep pack, tenant-scoped ({@code 4460} if no such pack for the tenant). */
    public Mono<ListingPrepPack> get(UUID id) {
        return TenantContextHolder.required().flatMap(ctx ->
                packs.findByTenantIdAndId(ctx.tenantId(), id)
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Listing prep pack not found", 4460, 404))));
    }

    /**
     * Approves a {@code DRAFTED} pack → {@code APPROVED} (copy-ready paste-out) and emits
     * {@code LISTING_PREP_APPROVED}. {@code 4460} if no such pack for the tenant; {@code 4461}/409 if it is
     * not {@code DRAFTED} (explicit-boolean status check — the RE-4 same-status guard).
     */
    public Mono<ListingPrepPack> approve(UUID id) {
        return TenantContextHolder.required().flatMap(ctx ->
                packs.findByTenantIdAndId(ctx.tenantId(), id)
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Listing prep pack not found", 4460, 404)))
                        .flatMap(pack -> {
                            if (pack.getStatus() != ListingPrepPack.Status.DRAFTED) {
                                return Mono.error(new DigiPresBeException(
                                        "Listing prep pack is not DRAFTED (status=" + pack.getStatus()
                                                + ") — cannot approve", 4461, 409));
                            }
                            pack.setStatus(ListingPrepPack.Status.APPROVED);
                            pack.setApprovedAt(Instant.now());
                            pack.setApprovedByUserId(ctx.userId());
                            return packs.save(pack).doOnNext(saved -> emitApproved(ctx.tenantId(), saved));
                        }));
    }

    /**
     * Skips a {@code DRAFTED} pack (the agent chose not to use it) → {@code SKIPPED}. {@code 4460} if no such
     * pack; {@code 4461}/409 if it is not {@code DRAFTED}.
     */
    public Mono<ListingPrepPack> skip(UUID id) {
        return TenantContextHolder.required().flatMap(ctx ->
                packs.findByTenantIdAndId(ctx.tenantId(), id)
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Listing prep pack not found", 4460, 404)))
                        .flatMap(pack -> {
                            if (pack.getStatus() != ListingPrepPack.Status.DRAFTED) {
                                return Mono.error(new DigiPresBeException(
                                        "Listing prep pack is not DRAFTED (status=" + pack.getStatus()
                                                + ") — cannot skip", 4461, 409));
                            }
                            pack.setStatus(ListingPrepPack.Status.SKIPPED);
                            return packs.save(pack);
                        }));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** The next Monday (the conventional listing-launch day) from today, UTC. */
    private static LocalDate nextMonday() {
        return LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }

    /** Replica of the RE-4 facts builder (a pure helper — kept local so RE-4's private surface is untouched). */
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

    /** Replica of the RE-4 photo-notes builder (a pure helper). */
    private static String buildPhotoNotes(List<PhotoNote> notes) {
        if (notes == null || notes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (PhotoNote n : notes) {
            sb.append("Photo ").append(i++).append(": ");
            if (n.getCaption() != null && !n.getCaption().isBlank()) {
                sb.append(n.getCaption());
            }
            if (n.getFeatures() != null && !n.getFeatures().isEmpty()) {
                sb.append(" [features: ").append(String.join(", ", n.getFeatures())).append("]");
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

    private void emitGenerated(UUID tenantId, ListingPrepPack pack) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("packId", pack.getId().toString());
        payload.put("listingId", pack.getListingId().toString());
        payload.put("calendarPostCount", pack.getSocialCalendar() == null ? 0 : pack.getSocialCalendar().size());
        payload.put("calendarHeldCount", pack.getCalendarHeldCount());
        payload.put("photoCaptionCount", pack.getPhotoCaptions() == null ? 0 : pack.getPhotoCaptions().size());
        payload.put("fairHousingFlagCount", pack.getFairHousingFlags() == null
                ? 0 : pack.getFairHousingFlags().size());
        payload.put("generationDegraded", pack.isGenerationDegraded());
        events.publish(DomainEvent.of(
                DomainEventType.LISTING_PREP_GENERATED, tenantId, pack.getId(), payload));
    }

    private void emitApproved(UUID tenantId, ListingPrepPack pack) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("packId", pack.getId().toString());
        payload.put("listingId", pack.getListingId().toString());
        events.publish(DomainEvent.of(
                DomainEventType.LISTING_PREP_APPROVED, tenantId, pack.getId(), payload));
    }

    /** A minimal per-post lint hit (term + snippet) before it is mapped to the pack's {@link FairHousingFlag}. */
    private record ListingMarketingFlag(String term, String snippet) {
    }
}
