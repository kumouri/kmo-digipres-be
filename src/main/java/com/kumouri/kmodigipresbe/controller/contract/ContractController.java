package com.kumouri.kmodigipresbe.controller.contract;

import com.kumouri.kmodigipresbe.model.contract.Contract;
import com.kumouri.kmodigipresbe.model.contract.Contract.Status;
import com.kumouri.kmodigipresbe.model.idempotency.IdempotentRoute;
import com.kumouri.kmodigipresbe.service.contract.ContractService;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * REST API for {@link Contract} resources (Phase F — F-D12).
 *
 * <p>Module-gated {@code @ConditionalOnProperty(kmosf.modules.contracts.enabled,
 * matchIfMissing=true)} (F-D13). Raw-entity in/out — no MapStruct DTOs.
 *
 * <h2>Key endpoints</h2>
 * <ul>
 *   <li>{@code POST /{id}/send} ({@link IdempotentRoute}) — renders the PDF, stores
 *       the rendered ref in S3, calls {@code DocumensoClient.sendForSignature},
 *       assigns {@code documensoDocumentId}/{@code sentAt}, assigns the contract
 *       number (first send only), transitions DRAFT→SENT. Idempotent — a re-send
 *       when already SENT returns the contract unchanged, no second Documenso
 *       document.</li>
 *   <li>{@code POST /quotes/{quoteId}/spawn-contract?templateId=} ({@link IdempotentRoute},
 *       F-D6) — spawns a DRAFT SOW Contract from an ACCEPTED Quote and an active
 *       template. Idempotent via explicit boolean in {@code ContractService.spawnFromQuote}.</li>
 *   <li>{@code GET /{id}/pdf} — renders and returns the current PDF bytes (signed
 *       PDF if signed, rendered PDF otherwise). Mirrors {@code QuoteController.pdf}.</li>
 * </ul>
 *
 * <h2>Send endpoint request body</h2>
 * The {@code POST /{id}/send} endpoint accepts an optional {@link SendRequest} with
 * {@code recipientEmail} and {@code recipientName} for the Documenso signature
 * request. Both may be null/blank (Documenso will use the document defaults).
 *
 * <p>Validation errors: {@code 3703} title blank, {@code 3704} quote not ACCEPTED,
 * {@code 3705} template not found/inactive, {@code 3706} voidReason missing,
 * {@code 3707} Contract not found, {@code 3709} illegal status transition.
 * Documenso errors: {@code 3720} apiToken missing, {@code 3721} send failed.
 */
@RestController
@RequestMapping("/contracts")
@ConditionalOnProperty(prefix = "kmosf.modules.contracts", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class ContractController {

    private final ContractService service;

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    @GetMapping
    public Flux<Contract> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public Mono<Contract> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Contract> create(@RequestBody Contract body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public Mono<Contract> update(@PathVariable UUID id, @RequestBody Contract body) {
        return service.update(id, body);
    }

    @PostMapping("/{id}/status")
    public Mono<Contract> setStatus(@PathVariable UUID id,
                                    @RequestParam Status status,
                                    @RequestParam(required = false) String voidReason) {
        return service.setStatus(id, status, voidReason);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return RoleGuard.requireRole("ADMIN").then(service.delete(id));
    }

    // -------------------------------------------------------------------------
    // Send to Documenso
    // -------------------------------------------------------------------------

    /**
     * Renders the contract PDF, stores it, sends to Documenso, assigns the
     * contract number, and transitions DRAFT→SENT. Idempotent — a contract that
     * already has a {@code documensoDocumentId} is returned unchanged; no second
     * Documenso document is ever created. {@link IdempotentRoute} is the HTTP-layer
     * belt; the {@code documensoDocumentId != null} explicit-boolean check in the
     * service is the domain-level guarantee.
     */
    @PostMapping("/{id}/send")
    @IdempotentRoute
    public Mono<Contract> send(@PathVariable UUID id,
                               @RequestBody(required = false) SendRequest request) {
        String email = request != null ? request.recipientEmail() : null;
        String name  = request != null ? request.recipientName()  : null;
        return service.send(id, email, name);
    }

    /**
     * Request body for {@code POST /{id}/send}.
     *
     * @param recipientEmail e-mail address to send the Documenso signature request to;
     *                       may be null (Documenso uses its own defaults)
     * @param recipientName  display name for the recipient; may be null
     */
    public record SendRequest(String recipientEmail, String recipientName) {}

    // -------------------------------------------------------------------------
    // Spawn contract from an accepted quote (F-D6)
    // -------------------------------------------------------------------------

    /**
     * Spawns a DRAFT SOW Contract from an ACCEPTED Quote and an active
     * {@code ContractTemplate} (F-D6). Idempotent via the explicit boolean
     * {@code existsByTenantIdAndQuoteIdAndTemplateId} probe in the service —
     * a second call with the same {@code (quoteId, templateId)} returns the
     * existing Contract rather than creating a second one.
     *
     * <p>Lives on {@code ContractController} (not {@code QuoteController}) so
     * contract creation is contract-owned — the Phase-C
     * {@code POST /projects/from-deal/{dealId}} on {@code ProjectController}
     * precedent.
     */
    @PostMapping("/quotes/{quoteId}/spawn-contract")
    @IdempotentRoute
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Contract> spawnFromQuote(@PathVariable UUID quoteId,
                                         @RequestParam UUID templateId) {
        return service.spawnFromQuote(quoteId, templateId);
    }

    // -------------------------------------------------------------------------
    // PDF download
    // -------------------------------------------------------------------------

    /**
     * Renders and returns the current contract PDF bytes. Returns the signed PDF
     * if the contract is in SIGNED status, otherwise renders fresh from the
     * template. Mirrors {@code QuoteController.pdf} ({@code DataBuffer} /
     * {@code Flux<DataBuffer>} streaming pattern).
     */
    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public Mono<ResponseEntity<Flux<DataBuffer>>> pdf(@PathVariable UUID id) {
        return service.renderPdfBytes(id).map(bytes -> {
            DataBuffer buf = new DefaultDataBufferFactory().wrap(bytes);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"contract-" + id + ".pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(Flux.just(buf));
        });
    }
}
