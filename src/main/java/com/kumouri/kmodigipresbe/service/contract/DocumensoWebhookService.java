package com.kumouri.kmodigipresbe.service.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.documenso.DocumensoClient;
import com.kumouri.kmodigipresbe.integration.documenso.DocumensoEventAdapter;
import com.kumouri.kmodigipresbe.integration.documenso.DocumensoEventAdapter.DocumensoEvent;
import com.kumouri.kmodigipresbe.integration.documenso.DocumensoSignatureVerifier;
import com.kumouri.kmodigipresbe.model.contract.Contract;
import com.kumouri.kmodigipresbe.model.contract.ContractTemplate;
import com.kumouri.kmodigipresbe.model.contract.DocumensoWebhookEvent;
import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import com.kumouri.kmodigipresbe.repository.contract.ContractRepository;
import com.kumouri.kmodigipresbe.repository.contract.DocumensoWebhookEventRepository;
import com.kumouri.kmodigipresbe.service.DealCrudService;
import com.kumouri.kmodigipresbe.service.project.ProjectService;
import com.kumouri.kmodigipresbe.service.storage.FileStorageService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Receives Documenso webhook events scoped to a single tenant and drives the
 * legal-signature path (Phase F — F-D7, the headline high-risk sub-phase F.6).
 *
 * <h2>Structural mirror of {@code StripeWebhookService} (F-D7, §9 item 1)</h2>
 * Every structural choice mirrors the shipped {@code StripeWebhookService}
 * line-shape-for-line-shape:
 * <ol>
 *   <li><strong>Connection lookup → secret → signature verify → parse.</strong>
 *       Signature failure → stable {@code 3710} / 401 (AC-F3).</li>
 *   <li><strong>Explicit-boolean idempotency probe</strong> on the Documenso
 *       event id:
 *       {@code documensoEvents.findByTenantIdAndDocumensoEventId(...).map(e -> true)
 *       .defaultIfEmpty(false).flatMap(seen -> seen ? Mono.empty() :
 *       processAndRecord(...))} — <strong>NEVER {@code switchIfEmpty(process)}</strong>
 *       (§9 item 3 — the mandated grep target).</li>
 *   <li><strong>Ledger-insert-FIRST</strong> in {@link #processAndRecord}: the
 *       {@link DocumensoWebhookEvent} row is saved BEFORE any side effect (signed-PDF
 *       store, Deal promotion). A concurrent re-delivery's second insert hits the
 *       unique {@code tenant_event_idx} → {@code DuplicateKeyException} →
 *       {@code Mono.empty()} = zero second effect.</li>
 *   <li><strong>Duplicate delivery → 200 no-op</strong> (NOT 409; a non-2xx
 *       causes Documenso to retry harder — the Stripe-precedent rationale).</li>
 *   <li><strong>Tenant resolved from the URL path</strong> → the per-tenant
 *       {@code IntegrationConnection} → never from the webhook payload (§9 item 5).</li>
 *   <li>All work under a synthetic {@code TenantContext(tenantId, null,
 *       Set.of("INTEGRATION_DOCUMENSO"))} via
 *       {@code body.contextWrite(TenantContextHolder.write(ctx))}.</li>
 * </ol>
 *
 * <h2>On DOCUMENT_SIGNED (AC-F1, AC-F2)</h2>
 * After the ledger gate, if the event type is {@code DOCUMENT_SIGNED}:
 * <ol>
 *   <li>Correlate the {@link Contract} by {@code documensoDocumentId} (3716 if
 *       absent — defensive).</li>
 *   <li>If the contract is already {@code SIGNED} → explicit-boolean no-op
 *       (re-delivery safety, in addition to the ledger gate).</li>
 *   <li>Download the signed PDF bytes via {@link DocumensoClient} (fallback to
 *       the download-URL if the adapter provided one, else fetch by document id).</li>
 *   <li>Store bytes via {@link FileStorageService#putBytes} (3717 on failure).
 *       The bytes are stored exactly as received — never re-rendered
 *       (legal-integrity invariant, §7).</li>
 *   <li>Set {@code signedAt}, {@code signedPdfStorageRef}, {@code status=SIGNED};
 *       save; publish {@code CONTRACT_SIGNED}.</li>
 *   <li><strong>F-D9 exactly-once SOW→WON+Project promotion</strong>: iff
 *       {@code kind == SOW && dealId != null && !promotedDealToWon} →
 *       {@code DealCrudService.moveStage(dealId, WON, null)} then
 *       {@code ProjectService.convertFromDeal(dealId)} (the <em>existing</em>
 *       services — no parallel path) → set {@code promotedDealToWon=true} +
 *       {@code spawnedProjectId} → save. Order is load-bearing: signed PDF
 *       durably stored BEFORE the deal moves.</li>
 * </ol>
 *
 * <h2>Payload-shape and digest-scheme boundaries (F-D7)</h2>
 * The Documenso webhook payload format is a <strong>known unknown</strong>. Every
 * payload-shape assumption lives in {@link DocumensoEventAdapter#parse} — no other
 * class parses the payload. The HMAC scheme lives in
 * {@link DocumensoSignatureVerifier} only. The header name ({@code X-Documenso-Signature})
 * is in the controller's {@code @RequestHeader} only.
 *
 * <h2>Error codes</h2>
 * {@code 3710} — HMAC invalid (401, AC-F3 stable errorCode);
 * {@code 3711} — invalid tenant UUID in path (400, in controller);
 * {@code 3712} — Documenso not connected for tenant (404);
 * {@code 3713} — webhookSigningSecret not configured (412);
 * {@code 3714} — body not JSON (400);
 * {@code 3715} — event missing id (400, defensive);
 * {@code 3716} — Contract not found for the Documenso document id (404, defensive);
 * {@code 3717} — signed-PDF fetch/store failed (502).
 *
 * <p>No live Documenso anywhere — signatures verified with a test signing secret in
 * tests (§7 hard boundary).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumensoWebhookService {

    static final String PROVIDER = "documenso";

    private final ObjectMapper objectMapper;
    private final IntegrationConnectionRepository connections;
    private final ContractRepository contractRepository;
    private final DocumensoWebhookEventRepository documensoEvents;
    private final DocumensoClient documensoClient;
    private final FileStorageService storage;
    private final DealCrudService dealCrudService;
    private final ProjectService projectService;
    private final DomainEventPublisher events;

    /**
     * Entry point called by {@code DocumensoWebhookController}.
     *
     * <p>Tenant is resolved from the URL path (the controller parses the UUID and
     * passes it here). The webhook payload's claimed tenant, if any, is NEVER
     * trusted — only the path-derived {@code IntegrationConnection} is authoritative.
     */
    public Mono<Void> handle(UUID tenantId, String secretHeader, String rawBody) {
        return connections.findByTenantIdAndProvider(tenantId, PROVIDER)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Documenso is not connected for this tenant", 3712, 404)))
                .flatMap(conn -> {
                    String secret = conn.getSecrets() == null
                            ? null
                            : conn.getSecrets().get("webhookSigningSecret");
                    if (secret == null || secret.isBlank()) {
                        return Mono.error(new DigiPresBeException(
                                "Tenant's Documenso webhookSigningSecret is not configured",
                                3713, 412));
                    }
                    // Webhook-secret verify — the ONLY place the secret is checked
                    // (F-D7). Real Documenso sends the secret verbatim in the
                    // X-Documenso-Secret header; the verifier does a constant-time
                    // equality vs the stored webhookSigningSecret. Failure → stable
                    // errorCode 3710, AC-F3.
                    if (!DocumensoSignatureVerifier.verify(secretHeader, rawBody, secret)) {
                        return Mono.error(new DigiPresBeException(
                                "Documenso webhook secret invalid", 3710, 401));
                    }
                    return process(tenantId, rawBody);
                });
    }

    private Mono<Void> process(UUID tenantId, String rawBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception ex) {
            return Mono.error(new DigiPresBeException(
                    "Documenso webhook body is not JSON: " + ex.getMessage(), 3714, 400));
        }

        // Delegate ALL payload parsing to DocumensoEventAdapter — the only class
        // that knows the assumed payload shape (F-D7 boundary).
        DocumensoEvent docEvent = DocumensoEventAdapter.parse(root);

        String eventId = docEvent.eventId();
        if (eventId == null || eventId.isBlank()) {
            // Defensive: without an event id we cannot dedupe — reject (400).
            return Mono.error(new DigiPresBeException(
                    "Documenso webhook event is missing its id", 3715, 400));
        }

        // Explicit-boolean idempotency probe on the EVENT id (F-D7 / §9 item 3).
        // NOT switchIfEmpty(processAndRecord) — that fires whenever the probe
        // completes empty and would re-process on a cache HIT.
        return documensoEvents.findByTenantIdAndDocumensoEventId(tenantId, eventId)
                .map(e -> true)
                .defaultIfEmpty(false)
                .flatMap(seen -> {
                    if (Boolean.TRUE.equals(seen)) {
                        // Duplicate delivery — acknowledge 200 no-op (NOT 409;
                        // Documenso retries harder on non-2xx).
                        log.debug("Documenso event {} already processed for tenant {} "
                                + "— 200 no-op", eventId, tenantId);
                        return Mono.empty();
                    }
                    return processAndRecord(tenantId, docEvent);
                });
    }

    /**
     * Ledger-insert FIRST (F-D7, §9 item 1), then dispatch.
     *
     * <p>The {@link DocumensoWebhookEvent} row is inserted <strong>before any side
     * effect</strong> so a concurrent re-delivery's second insert hits the unique
     * {@code tenant_event_idx} → {@code DuplicateKeyException} → {@code Mono.empty()}
     * = zero second effect.
     *
     * <p>All work runs under a synthetic {@code TenantContext} so tenant-scoped
     * repository calls inside {@link #handleDocumentSigned} resolve correctly
     * via {@code TenantContextHolder.required()}.
     */
    private Mono<Void> processAndRecord(UUID tenantId, DocumensoEvent docEvent) {
        String eventId = docEvent.eventId();
        String eventType = docEvent.type().name();

        DocumensoWebhookEvent ledger = DocumensoWebhookEvent.builder()
                .tenantId(tenantId)
                .documensoEventId(eventId)
                .eventType(eventType)
                .receivedAt(Instant.now())
                .build();

        // LEDGER-INSERT FIRST — the unique index is the hard gate against concurrent
        // re-delivery. onErrorResume(DuplicateKeyException) swallows the race winner's
        // duplicate → 200 no-op, zero second effect.
        Mono<Void> body = documensoEvents.save(ledger)
                .onErrorResume(DuplicateKeyException.class, e -> {
                    log.debug("Documenso event {} concurrently processed for tenant {} "
                            + "— 200 no-op", eventId, tenantId);
                    return Mono.empty();
                })
                .flatMap(savedLedger -> {
                    if (savedLedger == null) {
                        // Concurrent duplicate swallowed above — already Mono.empty()
                        return Mono.empty();
                    }
                    return switch (docEvent.type()) {
                        case DOCUMENT_SIGNED -> handleDocumentSigned(tenantId, docEvent, savedLedger);
                        case OTHER -> {
                            log.debug("Documenso event type {} for tenant {} — ledgered, 200 no-op",
                                    docEvent.type(), tenantId);
                            yield Mono.empty();
                        }
                    };
                });

        TenantContext webhookCtx = new TenantContext(
                tenantId, null, Set.of("INTEGRATION_DOCUMENSO"));
        return body.contextWrite(TenantContextHolder.write(webhookCtx));
    }

    /**
     * Handles a {@code DOCUMENT_SIGNED} event — the AC-F1 / AC-F2 headline path.
     *
     * <p>Order is load-bearing (F-D9):
     * <ol>
     *   <li>Correlate the Contract by documensoDocumentId (3716 if absent).</li>
     *   <li>Explicit-boolean already-SIGNED guard (re-delivery safety).</li>
     *   <li>Download signed PDF bytes.</li>
     *   <li>Store bytes in S3 via putBytes (3717 on failure).</li>
     *   <li>Persist signedAt / signedPdfStorageRef / status=SIGNED.</li>
     *   <li>Publish CONTRACT_SIGNED.</li>
     *   <li>If SOW + dealId present + !promotedDealToWon →
     *       moveStage(WON) + convertFromDeal (the EXISTING services, F-D9).</li>
     * </ol>
     */
    private Mono<Void> handleDocumentSigned(UUID tenantId, DocumensoEvent docEvent,
                                            DocumensoWebhookEvent savedLedger) {
        String documensoDocumentId = docEvent.documensoDocumentId();
        if (documensoDocumentId == null || documensoDocumentId.isBlank()) {
            log.warn("DOCUMENT_SIGNED event {} for tenant {} has no documentId — skipping",
                    docEvent.eventId(), tenantId);
            // Ledger row already written; return no-op rather than erroring the delivery.
            return Mono.empty();
        }

        return contractRepository.findByTenantIdAndDocumensoDocumentId(tenantId, documensoDocumentId)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "No Contract found for Documenso document id: " + documensoDocumentId,
                        3716, 404)))
                .flatMap(contract -> {
                    // Explicit-boolean: if already SIGNED — no-op (re-delivery safety,
                    // in addition to the ledger gate). NOT switchIfEmpty.
                    if (contract.getStatus() == Contract.Status.SIGNED) {
                        log.debug("Contract {} already SIGNED — 200 no-op re-delivery",
                                contract.getId());
                        return Mono.empty();
                    }

                    // Download the signed PDF bytes from Documenso.
                    // Primary: use the downloadUrl from the payload if present.
                    // Fallback: fetch by document id.
                    String downloadUrl = docEvent.signedDocumentDownloadUrl();
                    Mono<byte[]> fetchBytes = (downloadUrl != null && !downloadUrl.isBlank())
                            ? documensoClient.downloadSignedPdfFromUrl(downloadUrl)
                            : documensoClient.downloadSignedPdf(documensoDocumentId);

                    return fetchBytes
                            .onErrorMap(ex -> {
                                if (ex instanceof DigiPresBeException) return ex;
                                return new DigiPresBeException(
                                        "Signed-PDF download failed: " + ex.getMessage(),
                                        3717, 502);
                            })
                            .flatMap(pdfBytes -> storeSigned(tenantId, contract, pdfBytes, savedLedger));
                });
    }

    private Mono<Void> storeSigned(UUID tenantId, Contract contract,
                                   byte[] pdfBytes, DocumensoWebhookEvent savedLedger) {
        String partition = "contracts/" + contract.getId();
        return storage.putBytes(tenantId, partition, pdfBytes, "application/pdf", "pdf")
                .onErrorMap(ex -> {
                    if (ex instanceof DigiPresBeException) return ex;
                    return new DigiPresBeException(
                            "Signed-PDF store failed: " + ex.getMessage(), 3717, 502);
                })
                .flatMap(storageRef -> {
                    // Update the ledger row with the storage ref (audit/trace).
                    savedLedger.setContractId(contract.getId());
                    savedLedger.setSignedPdfStorageRef(storageRef);

                    // Persist signed state on the Contract.
                    contract.setSignedAt(Instant.now());
                    contract.setSignedPdfStorageRef(storageRef);
                    contract.setStatus(Contract.Status.SIGNED);

                    return documensoEvents.save(savedLedger)
                            .then(Mono.defer(() -> contractRepository.save(contract)))
                            .flatMap(savedContract -> {
                                // Publish CONTRACT_SIGNED (advisory).
                                publishContractSigned(tenantId, savedContract, storageRef);

                                // F-D9 exactly-once SOW→WON+Project promotion.
                                // Explicit-boolean gate: kind=SOW AND dealId present
                                // AND !promotedDealToWon.
                                if (savedContract.getKind() == ContractTemplate.Kind.SOW
                                        && savedContract.getDealId() != null
                                        && !savedContract.isPromotedDealToWon()) {
                                    return promote(savedContract);
                                }
                                return Mono.empty();
                            });
                });
    }

    /**
     * F-D9: synchronous exactly-once SOW→WON+Project promotion via the
     * <em>existing</em> {@link DealCrudService#moveStage} +
     * {@link ProjectService#convertFromDeal} path — no parallel logic, no
     * re-implementation (§9 item 4 invariant).
     *
     * <p>Doubly idempotent:
     * <ul>
     *   <li>The {@code !promotedDealToWon} gate on this {@link Contract}.</li>
     *   <li>{@code ProjectService.convertFromDeal}'s own
     *       {@code existsByTenantIdAndDealId} explicit-boolean guard (returns the
     *       existing Project if already present, {@code created=false}).</li>
     * </ul>
     */
    private Mono<Void> promote(Contract contract) {
        UUID dealId = contract.getDealId();
        log.info("SOW Contract {} signed — promoting deal {} to WON + spawning Project",
                contract.getId(), dealId);

        return dealCrudService.moveStage(dealId, PipelineStage.WON, null)
                .then(Mono.defer(() -> projectService.convertFromDeal(dealId)))
                .flatMap(result -> {
                    contract.setPromotedDealToWon(true);
                    contract.setSpawnedProjectId(result.project().getId());
                    return contractRepository.save(contract).then();
                });
    }

    private void publishContractSigned(UUID tenantId, Contract contract, String storageRef) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("contractId", contract.getId().toString());
        if (contract.getDealId() != null) {
            payload.put("dealId", contract.getDealId().toString());
        }
        payload.put("kind", contract.getKind().name());
        payload.put("signedPdfStorageRef", storageRef);
        events.publish(DomainEvent.of(
                DomainEventType.CONTRACT_SIGNED, tenantId, contract.getId(), payload));
    }
}
