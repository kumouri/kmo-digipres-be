package com.kumouri.kmodigipresbe.module.quoting.controller;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.quoting.controller.dto.QuoteResponse;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteRequest;
import com.kumouri.kmodigipresbe.module.quoting.service.QuoteIntakeService;
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
 * T8 (Home Services "QuoteNow") — the public homeowner intake endpoint. A homeowner (from the website
 * widget, or a truck/yard-sign QR that points here) submits an optional equipment photo + a
 * description / typed attributes as {@code multipart/form-data}; the BE returns an instant price range
 * + a repair-vs-replace recommendation in seconds.
 *
 * <h2>One public endpoint, tenant-from-token (the {@code EquipmentPhotoController} precedent)</h2>
 * {@code POST /public/integrations/quoting/{token}/quote}. The {@code {token}} is a
 * {@code PublicWidgetTokenService} HMAC token (widgetType {@code "quote-intake"}); {@link QuoteIntakeService}
 * verifies it and resolves the tenant from the token <strong>only</strong> — never the form fields.
 * Reached via the existing {@code /public/**} permitAll rule. <strong>The SMS/QR path is the same
 * endpoint</strong> reached from a QR that encodes this URL with the tenant's token — no separate
 * inbound-MMS webhook, no added 10DLC surface (the HS-2 design fork).
 *
 * <h2>WebFlux multipart — {@code getMultipartData()}, NOT {@code @RequestBody MultiValueMap}</h2>
 * The body is read via {@link ServerWebExchange#getMultipartData()} (the canonical WebFlux multipart
 * approach — the {@code EquipmentPhotoController}/{@code MoleTriageController} precedent). The
 * <strong>photo part is optional</strong> (the manual-only path is first-class — a homeowner who
 * doesn't snap a photo still gets a quote from typed attributes); when present, an unsupported media
 * type → {@code 4434}/415. Typed attributes come from {@link FormFieldPart#value()}.
 *
 * <h2>Module gate</h2>
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.quoting", name="enabled")} — default OFF; when
 * the module is disabled the bean is absent → endpoint not registered → 404 (the
 * {@code ServiceRequestWidgetController} "correct outcome"). This public route IS in the OpenAPI spec
 * when the module is on at generation time.
 */
@RestController
@RequestMapping("/public/integrations/quoting")
@ConditionalOnProperty(prefix = "kmosf.modules.quoting", name = "enabled")
public class QuoteIntakeController {

    /** The multipart part name for the (optional) photo. */
    public static final String IMAGE_PART = "image";

    private final QuoteIntakeService intakeService;

    public QuoteIntakeController(QuoteIntakeService intakeService) {
        this.intakeService = intakeService;
    }

    @PostMapping("/{token}/quote")
    public Mono<QuoteResponse> quote(@PathVariable String token, ServerWebExchange exchange) {
        return exchange.getMultipartData().flatMap(parts -> {
            Part imagePart = parts.getFirst(IMAGE_PART);
            byte[][] holder = new byte[1][];
            String mediaType;
            String filename;
            if (imagePart instanceof FilePart filePart) {
                mediaType = filePart.headers().getContentType() == null
                        ? null : filePart.headers().getContentType().toString();
                filename = filePart.filename();
                if (mediaType != null && !AiVisionService.isSupportedMediaType(mediaType)) {
                    return Mono.error(new DigiPresBeException(
                            "Unsupported image media type '" + mediaType
                                    + "' (accepted: image/jpeg, image/png, image/webp, image/gif)",
                            4434, 415));
                }
                return readBytes(filePart)
                        .flatMap(bytes -> intakeService.submit(token, bytes, mediaType, filename,
                                manualInput(parts)))
                        .map(QuoteResponse::from);
            }
            // No photo — the manual-only path.
            return intakeService.submit(token, null, null, null, manualInput(parts))
                    .map(QuoteResponse::from);
        });
    }

    private static QuoteIntakeService.ManualInput manualInput(MultiValueMap<String, Part> parts) {
        return new QuoteIntakeService.ManualInput(
                formValue(parts, "equipmentType"),
                formValue(parts, "brand"),
                intValue(parts, "ageYears"),
                formValue(parts, "failureMode"),
                formValue(parts, "problemDescription"),
                formValue(parts, "phone"),
                formValue(parts, "email"),
                formValue(parts, "name"));
    }

    private static Mono<byte[]> readBytes(FilePart filePart) {
        return DataBufferUtils.join(filePart.content())
                .map(QuoteIntakeController::toByteArray);
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

    private static Integer intValue(MultiValueMap<String, Part> parts, String name) {
        String v = formValue(parts, name);
        if (v == null || v.isBlank()) return null;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
