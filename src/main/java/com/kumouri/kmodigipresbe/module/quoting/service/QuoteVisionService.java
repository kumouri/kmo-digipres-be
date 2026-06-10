package com.kumouri.kmodigipresbe.module.quoting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.model.files.Attachment;
import com.kumouri.kmodigipresbe.module.quoting.model.AttributeSource;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteAttributes;
import com.kumouri.kmodigipresbe.repository.AttachmentRepository;
import com.kumouri.kmodigipresbe.service.ai.vision.AiVisionService;
import com.kumouri.kmodigipresbe.service.storage.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * T8 (Home Services "QuoteNow") — Q2: the vision auto-populate. Reads equipment attributes off a
 * homeowner's photo so the price-range synthesis can run with no typing. The
 * <strong>{@code extract}-shaped twin of {@code EquipmentVisionService}</strong>, but it returns the
 * parsed {@link QuoteAttributes} (it does NOT enrich a WorkOrder): store the photo
 * ({@code FileStorageService.putBytes} + an {@code Attachment(subjectType="QUOTE_REQUEST")}), call the
 * <strong>UNCHANGED</strong> shared {@link AiVisionService#extract} for an open-schema read, and
 * defensively parse it into {@link QuoteAttributes}. {@code AiVisionService}, {@code FileStorageService},
 * {@code AttachmentRepository} all stay empty-diff; this is an additive caller.
 *
 * <h2>AI is triage, not truth — vision is best-effort, never blocks the quote</h2>
 * A budget ({@code 1200}) / upstream ({@code 1202}) / parse failure degrades — via
 * {@link AiVisionService#extract}'s own empty-object fallback PLUS an {@code onErrorResume} here — to
 * an empty {@link QuoteAttributes} ({@code source=VISION, confidence=0}). The orchestrator then falls
 * back to the homeowner's typed manual attributes (or a diagnostic-visit quote), so the quote is
 * ALWAYS produced and the photo is ALWAYS stored. A read that finds nothing legible is the same.
 *
 * <h2>Confidence scoring</h2>
 * {@link QuoteAttributes#fromVisionJson} sets {@code confidence} to the fraction of the four
 * pricing-relevant fields (equipmentType / brand / age / failureMode) the model returned — surfaced
 * on the quote so the office sees how much to trust the auto-read.
 *
 * <h2>Blocking I/O off the Netty loop</h2>
 * The S3 {@code putBytes} store is the non-blocking {@code S3AsyncClient}; the base64 encode (inside
 * the reused {@code AiVisionService}) runs on {@code boundedElastic}. This service adds no blocking work.
 *
 * <p>Hand-constructed as a {@code @Bean} by {@code QuotingAutoConfiguration} when the module is on
 * (not component-scanned) so no bean exists when {@code kmosf.modules.quoting} is off. Runs under the
 * synthetic widget {@code TenantContext} the orchestrator establishes from the token.
 */
@Slf4j
public class QuoteVisionService {

    /** The {@code Attachment.subjectType} for a quote-intake equipment photo (bound to the QuoteRequest). */
    public static final String PHOTO_SUBJECT_TYPE = "QUOTE_REQUEST";
    /** The S3 partition (under the tenant root) quote photos are stored in. */
    private static final String STORAGE_PARTITION = "quote-photos";

    private static final String SYSTEM_PROMPT =
            "You look at a photo of a piece of home-services equipment (an HVAC condenser or furnace, "
            + "a water heater, an electrical panel, a pump, etc.) a homeowner is asking for a repair "
            + "or replacement quote on. Respond with ONLY a single minified JSON object and nothing "
            + "else — no prose, no markdown, no code fences. The object MUST have exactly these keys: "
            + "\"equipmentType\" (what the unit is, e.g. \"condenser\", \"furnace\", \"water heater\", "
            + "or null), \"brand\" (the manufacturer printed on it, or null), \"ageEstimateYears\" (a "
            + "whole-number estimate of the unit's age in years from its condition/labeling, or null), "
            + "and \"visibleFailureMode\" (any visible fault or condition you can see, e.g. \"rust on "
            + "coil\", \"corroded fitting\", \"leaking\", \"frost build-up\", or null). Use null for "
            + "anything you cannot determine; do not invent values. This is a triage hint a technician "
            + "will confirm on site.";

    private static final String USER_TEXT =
            "Identify this equipment for a repair-or-replace quote: return equipmentType, brand, "
            + "ageEstimateYears, and visibleFailureMode as the specified JSON.";

    private final FileStorageService storage;
    private final AttachmentRepository attachments;
    private final AiVisionService visionService;
    private final ObjectMapper objectMapper;
    private final String visionModel;

    public QuoteVisionService(FileStorageService storage,
                              AttachmentRepository attachments,
                              AiVisionService visionService,
                              ObjectMapper objectMapper,
                              String visionModel) {
        this.storage = storage;
        this.attachments = attachments;
        this.visionService = visionService;
        this.objectMapper = objectMapper;
        this.visionModel = visionModel;
    }

    /** The stored photo + the read attributes; the orchestrator persists both on the QuoteRequest. */
    public record VisionResult(UUID attachmentId, QuoteAttributes attributes) {
    }

    /**
     * Store the photo then read its attributes (best-effort). Must run under a {@code TenantContext}
     * (the orchestrator establishes the synthetic widget context from the token). A vision failure
     * leaves {@code attributes} empty ({@code source=VISION, confidence=0}) — never throws, never
     * drops the (already-stored) photo.
     */
    public Mono<VisionResult> readFromPhoto(UUID tenantId, byte[] imageBytes, String mediaType,
                                            String filename) {
        return storePhoto(tenantId, imageBytes, mediaType, filename)
                .flatMap(attachment -> readAttributes(imageBytes, mediaType, attachment.getId())
                        .map(attrs -> new VisionResult(attachment.getId(), attrs)));
    }

    private Mono<Attachment> storePhoto(UUID tenantId, byte[] imageBytes, String mediaType,
                                        String filename) {
        String suffix = suffixFor(mediaType, filename);
        return storage.putBytes(tenantId, STORAGE_PARTITION, imageBytes, mediaType, suffix)
                .flatMap(storageRef -> attachments.save(Attachment.builder()
                        .subjectType(PHOTO_SUBJECT_TYPE)
                        .filename(filename != null && !filename.isBlank() ? filename : "equipment." + suffix)
                        .contentType(mediaType)
                        .sizeBytes((long) imageBytes.length)
                        .storageRef(storageRef)
                        .build()));
    }

    private Mono<QuoteAttributes> readAttributes(byte[] imageBytes, String mediaType, UUID attachmentId) {
        return visionService.extract(imageBytes, mediaType, visionModel, SYSTEM_PROMPT, USER_TEXT)
                .map(QuoteAttributes::fromVisionJson)
                .onErrorResume(e -> {
                    log.warn("QuoteNow vision read failed for attachment {} (best-effort, manual "
                            + "fallback): {}", attachmentId, e.getMessage());
                    return Mono.just(QuoteAttributes.builder()
                            .source(AttributeSource.VISION)
                            .confidence(0.0)
                            .build());
                });
    }

    private static String suffixFor(String mediaType, String filename) {
        if (filename != null && filename.contains(".")) {
            String ext = filename.substring(filename.lastIndexOf('.') + 1).trim();
            if (!ext.isEmpty() && ext.length() <= 5) return ext.toLowerCase();
        }
        if (mediaType == null) return "jpg";
        return switch (mediaType.trim().toLowerCase()) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "jpg";
        };
    }
}
