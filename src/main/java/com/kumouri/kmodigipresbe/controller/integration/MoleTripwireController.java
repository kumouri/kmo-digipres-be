package com.kumouri.kmodigipresbe.controller.integration;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.moletripwire.MoleTripwireResponse;
import com.kumouri.kmodigipresbe.integration.moletripwire.MoleTripwireService;
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
 * Unauthenticated public photo-report endpoint for the NMM B2 re-activity tripwire (Phase 3 —
 * coverage-window automation). A coverage customer follows the per-customer tokenized link Rob
 * texted them and POSTs a phone photo (+ optional note) as {@code multipart/form-data}; the BE
 * classifies it (mole/vole/gopher/none/unsure + confidence), stores the image, and — on a
 * high-confidence pest — creates a re-treatment {@code Milestone} on the customer's {@code Project}
 * + notifies Rob.
 *
 * <h2>Auth — path token only, never the payload</h2>
 * The {@code {token}} path segment is an HMAC {@code mole-tripwire} token issued by
 * {@link com.kumouri.kmodigipresbe.integration.moletripwire.MoleTripwireTokenService} carrying the
 * issuing tenant id AND the coverage customer's Project id. {@link MoleTripwireService} verifies it
 * and resolves both the tenant and the Project from the token <strong>only</strong> — never from the
 * multipart fields. Generic token rejections surface {@code 1600-1603}; a wrong-widget-type token
 * surfaces {@code 4013}.
 *
 * <h2>WebFlux multipart reading — {@code getMultipartData()}, NOT {@code @RequestBody MultiValueMap}</h2>
 * The body is read via {@link ServerWebExchange#getMultipartData()} (the canonical WebFlux multipart
 * approach — the Phase-2 {@code MoleTriageController} precedent), NOT {@code @RequestBody
 * MultiValueMap} (the Phase-1 415 trap). The image {@link FilePart} bytes are joined off the body
 * publisher; the optional {@code note} comes from {@link FormFieldPart#value()}. A missing image
 * part → {@code 4014}/400.
 *
 * <h2>Module gate</h2>
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.mole-tripwire", name="enabled",
 * matchIfMissing=true)} — on by default; mirrors the Phase-1/2 controllers (a disabled module →
 * endpoint not registered → 404). Reached via the {@code /public/**} permitAll rule.
 */
@RestController
@RequestMapping("/public/integrations/mole-tripwire")
@ConditionalOnProperty(prefix = "kmosf.modules.mole-tripwire", name = "enabled",
        matchIfMissing = true)
@RequiredArgsConstructor
public class MoleTripwireController {

    /** The multipart part name the FE uses for the photo. */
    public static final String IMAGE_PART = "image";

    private final MoleTripwireService tripwire;

    /**
     * Accepts a coverage customer's tripwire photo + an optional note and returns the classification
     * (plus the re-treatment milestone id when one was created).
     *
     * @param token    the mole-tripwire token (from the URL path — proves the tenant AND the Project;
     *                 NEVER trusts a tenant/project id from the payload)
     * @param exchange the request (the multipart body is read via {@code getMultipartData()})
     */
    @PostMapping("/{token}/report")
    public Mono<MoleTripwireResponse> report(
            @PathVariable String token,
            ServerWebExchange exchange) {
        return exchange.getMultipartData().flatMap(parts -> {
            Part imagePart = parts.getFirst(IMAGE_PART);
            if (!(imagePart instanceof FilePart filePart)) {
                return Mono.error(new DigiPresBeException(
                        "Mole-tripwire report is missing its '" + IMAGE_PART + "' image part",
                        4014, 400));
            }
            String note = formValue(parts, "note");
            String mediaType = filePart.headers().getContentType() == null
                    ? null
                    : filePart.headers().getContentType().toString();
            return readBytes(filePart)
                    .flatMap(bytes -> tripwire.report(token, bytes, mediaType, filePart.filename(), note));
        });
    }

    /** Joins a {@link FilePart}'s content into a single {@code byte[]} (releases the buffer). */
    private static Mono<byte[]> readBytes(FilePart filePart) {
        return DataBufferUtils.join(filePart.content())
                .map(MoleTripwireController::toByteArray);
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
