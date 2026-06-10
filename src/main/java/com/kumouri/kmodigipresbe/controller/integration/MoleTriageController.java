package com.kumouri.kmodigipresbe.controller.integration;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.molevision.MolePhotoIntakeParams;
import com.kumouri.kmodigipresbe.integration.molevision.MoleTriageResponse;
import com.kumouri.kmodigipresbe.integration.molevision.MoleTriageService;
import lombok.RequiredArgsConstructor;
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
 * Unauthenticated public photo-intake endpoint for the NMM "is this a mole?" tool (Phase 2 —
 * Feature B). A homeowner POSTs a phone photo (+ optional name/phone/email/address) as
 * {@code multipart/form-data}; the BE classifies it (mole/vole/gopher/none/unsure + confidence),
 * stores the image, records a lead + Activity, notifies Rob, and returns the classification.
 *
 * <h2>Auth — path token only, never the payload</h2>
 * The {@code {token}} path segment is an HMAC widget token issued by
 * {@link com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService} (the
 * {@code ServiceRequestWidgetController} precedent) carrying the issuing tenant id and a
 * {@code "mole-triage"} {@code widgetType} claim. {@link MoleTriageService} verifies it and
 * resolves the tenant from the token <strong>only</strong> — never from the multipart fields — so
 * no stranger can burn another tenant's AI budget against an arbitrary tenant id. Token rejections
 * surface {@code 1600-1603}; a wrong-widget-type token surfaces {@code 4010}.
 *
 * <h2>WebFlux multipart reading — the §-boundary, NOT {@code @RequestBody MultiValueMap}</h2>
 * The body is read via {@link ServerWebExchange#getMultipartData()} (the canonical WebFlux
 * multipart approach), NOT {@code @RequestBody MultiValueMap} (the Phase-1 415 trap). The image
 * {@link FilePart} bytes are joined off the body publisher; the optional text fields come from
 * {@link FormFieldPart#value()}. A missing image part → {@code 4011}/400.
 *
 * <h2>Module gate</h2>
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.mole-triage", name="enabled",
 * matchIfMissing=true)} — on by default; mirrors the Phase-1 voicemail controllers (a disabled
 * module → endpoint not registered → 404, the {@code ServiceRequestWidgetController} "correct
 * outcome"). Reached via the {@code /public/**} permitAll rule.
 */
@RestController
@RequestMapping("/public/integrations/mole-triage")
@ConditionalOnProperty(prefix = "kmosf.modules.mole-triage", name = "enabled",
        matchIfMissing = true)
@RequiredArgsConstructor
public class MoleTriageController {

    /** The multipart part name the FE uses for the photo. */
    public static final String IMAGE_PART = "image";

    /**
     * Security fix BE-11 — hard cap on the joined image bytes (15 MB; ample for a phone
     * photo). {@link DataBufferUtils#join(org.reactivestreams.Publisher, int)} aborts with a
     * {@link org.springframework.core.io.buffer.DataBufferLimitException} once the accumulated
     * size would exceed this, BEFORE the whole body is materialized in heap — so an oversized /
     * chunked upload is a bounded 413, not an OOM. Backstops the
     * {@code spring.webflux.multipart.*} / {@code spring.codec.max-in-memory-size} caps.
     */
    static final int MAX_IMAGE_BYTES = 15 * 1024 * 1024;

    private final MoleTriageService triage;

    /**
     * Accepts a homeowner photo + optional contact text fields and returns the classification.
     *
     * @param token    the mole-triage widget token (from the URL path — proves the tenant; NEVER
     *                 trusts a tenant id from the payload)
     * @param exchange the request (the multipart body is read via {@code getMultipartData()})
     */
    @PostMapping("/{token}/classify")
    public Mono<MoleTriageResponse> classify(
            @PathVariable String token,
            ServerWebExchange exchange) {
        return exchange.getMultipartData().flatMap(parts -> {
            Part imagePart = parts.getFirst(IMAGE_PART);
            if (!(imagePart instanceof FilePart filePart)) {
                return Mono.error(new DigiPresBeException(
                        "Mole-triage submission is missing its '" + IMAGE_PART + "' image part",
                        4011, 400));
            }
            MolePhotoIntakeParams intake = MolePhotoIntakeParams.of(
                    formValue(parts, "name"),
                    formValue(parts, "phone"),
                    formValue(parts, "email"),
                    formValue(parts, "address"));
            String mediaType = filePart.headers().getContentType() == null
                    ? null
                    : filePart.headers().getContentType().toString();
            return readBytes(filePart)
                    .flatMap(bytes -> triage.triage(token, bytes, mediaType, filePart.filename(), intake));
        });
    }

    /**
     * Joins a {@link FilePart}'s content into a single {@code byte[]} (releases the buffer),
     * capped at {@link #MAX_IMAGE_BYTES} (security fix BE-11). The bounded
     * {@link DataBufferUtils#join(org.reactivestreams.Publisher, int)} aborts early on
     * oversize; we map its {@link org.springframework.core.io.buffer.DataBufferLimitException}
     * to a {@code 413} ({@code 4011} reused — the same image-rejection code family).
     */
    private static Mono<byte[]> readBytes(FilePart filePart) {
        return DataBufferUtils.join(filePart.content(), MAX_IMAGE_BYTES)
                .map(MoleTriageController::toByteArray)
                .onErrorMap(
                        org.springframework.core.io.buffer.DataBufferLimitException.class,
                        ex -> new DigiPresBeException(
                                "Uploaded image exceeds the " + (MAX_IMAGE_BYTES / (1024 * 1024))
                                        + " MB limit", 4011, 413));
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

    /** Reads an optional text part's value (null if absent or not a form field). */
    private static String formValue(MultiValueMap<String, Part> parts, String name) {
        Part p = parts.getFirst(name);
        return (p instanceof FormFieldPart ff) ? ff.value() : null;
    }
}
