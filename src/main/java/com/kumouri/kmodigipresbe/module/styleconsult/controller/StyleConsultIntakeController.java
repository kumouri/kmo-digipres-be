package com.kumouri.kmodigipresbe.module.styleconsult.controller;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.styleconsult.controller.dto.StyleConsultResponse;
import com.kumouri.kmodigipresbe.module.styleconsult.service.StyleConsultService;
import com.kumouri.kmodigipresbe.service.ai.vision.AiVisionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * T9 (Salon "StyleConsult AI") — the public prospect intake endpoint. A prospect (from the salon's
 * website widget, or an Instagram-bio/QR link) submits an optional <strong>inspiration photo</strong>
 * + an optional note / typed style attributes as {@code multipart/form-data}; the BE returns instant
 * service + margin-aware retail recommendations in seconds, then a path to book.
 *
 * <h2>One public endpoint, tenant-from-token (the {@code QuoteIntakeController} precedent)</h2>
 * {@code POST /public/integrations/styleconsult/{token}/consult}. The {@code {token}} is a
 * {@code PublicWidgetTokenService} HMAC token (widgetType {@code "style-consult"});
 * {@link StyleConsultService} verifies it and resolves the tenant from the token <strong>only</strong>
 * — never the form fields. Reached via the existing {@code /public/**} permitAll rule.
 *
 * <h2>WebFlux multipart — {@code getMultipartData()}, NOT {@code @RequestBody MultiValueMap}</h2>
 * The body is read via {@link ServerWebExchange#getMultipartData()} (the canonical WebFlux multipart
 * approach — the {@code QuoteIntakeController}/{@code MoleTriageController} precedent). The
 * <strong>photo part is optional</strong> (the manual/notes path is first-class — a prospect who only
 * describes the look still gets recommendations); when present, an unsupported media type →
 * {@code 4454}/415. Typed attributes come from {@link FormFieldPart#value()}.
 *
 * <h2>Module gate</h2>
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.chairfill", name="enabled")} — the salon flagship
 * key, default OFF; when the module is disabled the bean is absent → endpoint not registered → 404 (the
 * {@code ServiceRequestWidgetController} "correct outcome").
 */
@RestController
@RequestMapping("/public/integrations/styleconsult")
@ConditionalOnProperty(prefix = "kmosf.modules.chairfill", name = "enabled")
public class StyleConsultIntakeController {

    /** The multipart part name for the (optional) inspiration photo. */
    public static final String IMAGE_PART = "image";

    /**
     * Security fix AI-02 — hard cap on the joined image bytes (15 MB; ample for an inspiration photo).
     * {@link DataBufferUtils#join(org.reactivestreams.Publisher, int)} aborts with a
     * {@link org.springframework.core.io.buffer.DataBufferLimitException} once the accumulated size
     * would exceed this, BEFORE the whole body is materialized in heap — so an oversized / chunked
     * upload is a bounded 413, not an OOM. Mirrors {@code MoleTriageController.MAX_IMAGE_BYTES};
     * backstops the global {@code spring.codec.max-in-memory-size} ceiling.
     */
    static final int MAX_IMAGE_BYTES = 15 * 1024 * 1024;

    private final StyleConsultService consultService;

    public StyleConsultIntakeController(StyleConsultService consultService) {
        this.consultService = consultService;
    }

    @PostMapping("/{token}/consult")
    public Mono<StyleConsultResponse> consult(@PathVariable String token, ServerWebExchange exchange) {
        return exchange.getMultipartData().flatMap(parts -> {
            Part imagePart = parts.getFirst(IMAGE_PART);
            if (imagePart instanceof FilePart filePart) {
                String mediaType = filePart.headers().getContentType() == null
                        ? null : filePart.headers().getContentType().toString();
                String filename = filePart.filename();
                if (mediaType != null && !AiVisionService.isSupportedMediaType(mediaType)) {
                    return Mono.error(new DigiPresBeException(
                            "Unsupported image media type '" + mediaType
                                    + "' (accepted: image/jpeg, image/png, image/webp, image/gif)",
                            4454, 415));
                }
                return readBytes(filePart)
                        .flatMap(bytes -> consultService.submit(token, bytes, mediaType, filename,
                                manualInput(parts)))
                        .map(StyleConsultResponse::from);
            }
            // No photo — the manual/notes-only path.
            return consultService.submit(token, null, null, null, manualInput(parts))
                    .map(StyleConsultResponse::from);
        });
    }

    private static StyleConsultService.ManualInput manualInput(MultiValueMap<String, Part> parts) {
        return new StyleConsultService.ManualInput(
                formValue(parts, "styleCategory"),
                formValue(parts, "length"),
                formValue(parts, "texture"),
                formValue(parts, "color"),
                formValue(parts, "notes"),
                formValue(parts, "phone"),
                formValue(parts, "email"),
                formValue(parts, "name"));
    }

    /**
     * Joins a {@link FilePart}'s content into a single {@code byte[]} (releases the buffer), capped at
     * {@link #MAX_IMAGE_BYTES} (security fix AI-02). The bounded
     * {@link DataBufferUtils#join(org.reactivestreams.Publisher, int)} aborts early on oversize; we map
     * its {@link org.springframework.core.io.buffer.DataBufferLimitException} to a {@code 413}
     * ({@code 4454} reused — the same image-rejection code family as the unsupported-media-type guard).
     */
    private static Mono<byte[]> readBytes(FilePart filePart) {
        return DataBufferUtils.join(filePart.content(), MAX_IMAGE_BYTES)
                .map(StyleConsultIntakeController::toByteArray)
                .onErrorMap(
                        org.springframework.core.io.buffer.DataBufferLimitException.class,
                        ex -> new DigiPresBeException(
                                "Uploaded image exceeds the " + (MAX_IMAGE_BYTES / (1024 * 1024))
                                        + " MB limit", 4454, 413));
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

    private static String formValue(MultiValueMap<String, Part> parts, String name) {
        Part p = parts.getFirst(name);
        return (p instanceof FormFieldPart ff) ? ff.value() : null;
    }
}
