package com.kumouri.kmodigipresbe.module.homeservices.controller;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.equipmentvision.EquipmentPhotoResponse;
import com.kumouri.kmodigipresbe.integration.equipmentvision.EquipmentVisionService;
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
 * Unauthenticated public equipment-photo upload endpoint for Home Services (HS-2 — "Front Desk That
 * Never Sleeps"). After a home-services voicemail creates a DRAFT {@code WorkOrder} (HS-1), the
 * caller follows the tokenized link the HS-1 auto-ack SMS carried and POSTs a phone photo of the
 * equipment (+ an optional note) as {@code multipart/form-data}; the BE reads the nameplate with
 * vision, stores the image, enriches the bound WorkOrder + logs an Activity + enriches the owner
 * digest, and returns what it read.
 *
 * <h2>Design fork (plan §3) — tokenized link, NOT an inbound-MMS webhook</h2>
 * The photo arrives over this tokenized HTTPS upload (the proven {@code MoleTriageController} /
 * {@code MoleTripwireController} pattern), so HS-2 adds <strong>no new inbound Twilio MMS webhook and
 * no added 10DLC surface</strong>. The token encodes the tenant + the target WorkOrder, so
 * correlation is trivial.
 *
 * <h2>Auth — path token only, never the payload</h2>
 * The {@code {token}} path segment is an HMAC {@code equipment-photo} token issued by
 * {@link com.kumouri.kmodigipresbe.integration.equipmentvision.EquipmentPhotoTokenService} carrying
 * the issuing tenant id AND the DRAFT WorkOrder id. {@link EquipmentVisionService} verifies it and
 * resolves both the tenant and the WorkOrder from the token <strong>only</strong> — never from the
 * multipart fields. Generic token rejections surface {@code 1600-1603}; a wrong-widget-type token
 * surfaces {@code 4210}.
 *
 * <h2>WebFlux multipart reading — {@code getMultipartData()}, NOT {@code @RequestBody MultiValueMap}</h2>
 * The body is read via {@link ServerWebExchange#getMultipartData()} (the canonical WebFlux multipart
 * approach — the {@code MoleTriageController}/{@code MoleTripwireController} precedent), NOT
 * {@code @RequestBody MultiValueMap}. The image {@link FilePart} bytes are joined off the body
 * publisher; the optional {@code note} comes from {@link FormFieldPart#value()}. A missing image
 * part → {@code 4213}/400.
 *
 * <h2>Module gate</h2>
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.home-services", name="enabled")} — strict
 * opt-in (NOT {@code matchIfMissing}); when home-services is disabled the bean is absent → endpoint
 * not registered → 404 (the HS-1 {@code MissedCallInboxController} precedent). Reached via the
 * {@code /public/**} permitAll rule.
 */
@RestController
@RequestMapping("/public/integrations/home-services/equipment-photo")
@ConditionalOnProperty(prefix = "kmosf.modules.home-services", name = "enabled")
@RequiredArgsConstructor
public class EquipmentPhotoController {

    /** The multipart part name the FE uses for the photo. */
    public static final String IMAGE_PART = "image";

    private final EquipmentVisionService equipmentVision;

    /**
     * Accepts a caller's equipment photo + an optional note and returns the nameplate read.
     *
     * @param token    the equipment-photo token (from the URL path — proves the tenant AND the
     *                 WorkOrder; NEVER trusts a tenant/work-order id from the payload)
     * @param exchange the request (the multipart body is read via {@code getMultipartData()})
     */
    @PostMapping("/{token}/upload")
    public Mono<EquipmentPhotoResponse> upload(
            @PathVariable String token,
            ServerWebExchange exchange) {
        return exchange.getMultipartData().flatMap(parts -> {
            Part imagePart = parts.getFirst(IMAGE_PART);
            if (!(imagePart instanceof FilePart filePart)) {
                return Mono.error(new DigiPresBeException(
                        "Equipment-photo upload is missing its '" + IMAGE_PART + "' image part",
                        4213, 400));
            }
            String note = formValue(parts, "note");
            String mediaType = filePart.headers().getContentType() == null
                    ? null
                    : filePart.headers().getContentType().toString();
            return readBytes(filePart)
                    .flatMap(bytes -> equipmentVision.submit(
                            token, bytes, mediaType, filePart.filename(), note));
        });
    }

    /** Joins a {@link FilePart}'s content into a single {@code byte[]} (releases the buffer). */
    private static Mono<byte[]> readBytes(FilePart filePart) {
        return DataBufferUtils.join(filePart.content())
                .map(EquipmentPhotoController::toByteArray);
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
