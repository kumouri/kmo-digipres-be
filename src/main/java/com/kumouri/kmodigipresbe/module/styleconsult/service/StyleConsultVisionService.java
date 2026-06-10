package com.kumouri.kmodigipresbe.module.styleconsult.service;

import com.kumouri.kmodigipresbe.model.files.Attachment;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleAttributeSource;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleAttributes;
import com.kumouri.kmodigipresbe.repository.AttachmentRepository;
import com.kumouri.kmodigipresbe.service.ai.vision.AiVisionService;
import com.kumouri.kmodigipresbe.service.storage.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * T9 (Salon "StyleConsult AI") — S1: the vision read of a prospect's <strong>inspiration photo</strong>.
 * The salon-flavored twin of the T8 {@code QuoteVisionService}: store the photo
 * ({@code FileStorageService.putBytes} + an {@code Attachment(subjectType="STYLE_CONSULT")}), call the
 * <strong>UNCHANGED</strong> shared {@link AiVisionService#extract} for an open-schema read of the
 * hair/style attributes, and defensively parse it into {@link StyleAttributes}. {@code AiVisionService},
 * {@code FileStorageService}, {@code AttachmentRepository} all stay empty-diff; this is an additive caller.
 *
 * <h2>AI is triage, not truth — vision is best-effort, never blocks the consult</h2>
 * A budget ({@code 1200}) / upstream ({@code 1202}) / parse failure degrades — via
 * {@link AiVisionService#extract}'s own empty-object fallback PLUS an {@code onErrorResume} here — to
 * an empty {@link StyleAttributes} ({@code source=VISION, confidence=0}). The orchestrator then falls
 * back to the prospect's typed manual attributes (or a generic consult), so the consult is ALWAYS
 * produced and the photo is ALWAYS stored. A read that finds nothing legible is the same.
 *
 * <h2>Confidence scoring</h2>
 * {@link StyleAttributes#fromVisionJson} sets {@code confidence} to the fraction of the four
 * style-relevant fields (styleCategory / length / texture / color) the model returned — surfaced on
 * the consult so the coordinator sees how much to trust the auto-read.
 *
 * <h2>Blocking I/O off the Netty loop</h2>
 * The S3 {@code putBytes} store is the non-blocking {@code S3AsyncClient}; the base64 encode (inside
 * the reused {@code AiVisionService}) runs on {@code boundedElastic}. This service adds no blocking work.
 *
 * <p>Hand-constructed as a {@code @Bean} by {@code StyleConsultAutoConfiguration} when the module is on
 * (not component-scanned) so no bean exists when the salon flagship is off. Runs under the synthetic
 * widget {@code TenantContext} the orchestrator establishes from the token.
 */
@Slf4j
public class StyleConsultVisionService {

    /** The {@code Attachment.subjectType} for a style-consult inspiration photo (bound to the StyleConsult). */
    public static final String PHOTO_SUBJECT_TYPE = "STYLE_CONSULT";
    /** The S3 partition (under the tenant root) inspiration photos are stored in. */
    private static final String STORAGE_PARTITION = "style-consult-photos";

    private static final String SYSTEM_PROMPT =
            "You are a salon stylist's assistant. You look at an inspiration photo a prospective client "
            + "shares of a hair look they want. Respond with ONLY a single minified JSON object and "
            + "nothing else — no prose, no markdown, no code fences. The object MUST have exactly these "
            + "keys: \"styleCategory\" (the look, e.g. \"balayage\", \"blonde highlights\", \"bob cut\", "
            + "\"curls\", \"keratin smoothing\", or null), \"length\" (\"short\", \"medium\", or "
            + "\"long\", or null), \"texture\" (\"straight\", \"wavy\", \"curly\", or \"coily\", or "
            + "null), and \"color\" (the dominant/target hair color, e.g. \"blonde\", \"brunette\", "
            + "\"balayage\", or null). Use null for anything you cannot determine; do not invent values. "
            + "This is a styling hint a human stylist will confirm in person.";

    private static final String USER_TEXT =
            "Assess this inspiration photo for a salon consult: return styleCategory, length, texture, "
            + "and color as the specified JSON.";

    private final FileStorageService storage;
    private final AttachmentRepository attachments;
    private final AiVisionService visionService;
    private final String visionModel;

    public StyleConsultVisionService(FileStorageService storage,
                                     AttachmentRepository attachments,
                                     AiVisionService visionService,
                                     String visionModel) {
        this.storage = storage;
        this.attachments = attachments;
        this.visionService = visionService;
        this.visionModel = visionModel;
    }

    /** The stored photo + the read attributes; the orchestrator persists both on the StyleConsult. */
    public record VisionResult(UUID attachmentId, StyleAttributes attributes) {
    }

    /**
     * Store the photo then read its style attributes (best-effort). Must run under a
     * {@code TenantContext} (the orchestrator establishes the synthetic widget context from the token).
     * A vision failure leaves {@code attributes} empty ({@code source=VISION, confidence=0}) — never
     * throws, never drops the (already-stored) photo.
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
                        .filename(filename != null && !filename.isBlank() ? filename : "inspiration." + suffix)
                        .contentType(mediaType)
                        .sizeBytes((long) imageBytes.length)
                        .storageRef(storageRef)
                        .build()));
    }

    private Mono<StyleAttributes> readAttributes(byte[] imageBytes, String mediaType, UUID attachmentId) {
        return visionService.extract(imageBytes, mediaType, visionModel, SYSTEM_PROMPT, USER_TEXT)
                .map(StyleAttributes::fromVisionJson)
                .onErrorResume(e -> {
                    log.warn("StyleConsult vision read failed for attachment {} (best-effort, manual "
                            + "fallback): {}", attachmentId, e.getMessage());
                    return Mono.just(StyleAttributes.builder()
                            .source(StyleAttributeSource.VISION)
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
