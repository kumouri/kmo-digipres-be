package com.kumouri.kmodigipresbe.controller.portal;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.contract.Contract;
import com.kumouri.kmodigipresbe.model.response.PortalContractSummary;
import com.kumouri.kmodigipresbe.service.portal.PortalContractsService;
import com.kumouri.kmodigipresbe.service.portal.PortalLinkedContactResolver;
import com.kumouri.kmodigipresbe.service.portal.PortalOwnershipGuard;
import com.kumouri.kmodigipresbe.service.storage.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Portal contracts surface (Phase G — G.4, G-D1, G-D6).
 *
 * <p>All data funnels through {@link PortalLinkedContactResolver} (list) or
 * {@link PortalOwnershipGuard#requireOwnedContract} (single). No
 * {@code @ConditionalOnProperty} — the portal chain is the gate.
 *
 * <h2>Signed-PDF download (G-D6)</h2>
 * {@code GET /contracts/{id}/signed-pdf} presign-downloads the stored
 * {@code signedPdfStorageRef} — never streams bytes through the BE. Rejects
 * with {@code 3806 / 409} when the contract is not SIGNED or no signed-PDF
 * storage ref exists. The raw ref is NEVER exposed to the portal (G-D1).
 * The foreign-tenant key guard ({@code 1311}) lives inside
 * {@link FileStorageService#presignDownload} as defence-in-depth.
 */
@RestController
@RequestMapping("/portal/me")
@RequiredArgsConstructor
public class PortalContractsController {

    private static final Duration PRESIGN_TTL = Duration.ofMinutes(15);

    private final PortalLinkedContactResolver linkedContact;
    private final PortalContractsService portalContractsService;
    private final PortalOwnershipGuard ownershipGuard;
    private final FileStorageService fileStorage;

    @GetMapping("/contracts")
    public Flux<PortalContractSummary> listContracts() {
        return linkedContact.resolve()
                .flatMapMany(portalContractsService::listForContact);
    }

    @GetMapping("/contracts/{id}")
    public Mono<PortalContractSummary> getContract(@PathVariable UUID id) {
        return ownershipGuard.requireOwnedContract(id)
                .map(PortalContractSummary::from);
    }

    /**
     * Returns a presigned download URL for the signed PDF of an owned contract.
     * Requires {@code status == SIGNED && signedPdfStorageRef != null}; otherwise
     * returns {@code 3806 / 409}. Presign-URL only — no bytes stream through the BE
     * (G-D6 hard line).
     */
    @GetMapping("/contracts/{id}/signed-pdf")
    public Mono<SignedPdfResponse> getSignedPdf(@PathVariable UUID id) {
        return ownershipGuard.requireOwnedContract(id)
                .flatMap(contract -> {
                    if (contract.getStatus() != Contract.Status.SIGNED
                            || contract.getSignedPdfStorageRef() == null) {
                        return Mono.error(new DigiPresBeException(
                                "Signed PDF is not available for this contract", 3806, 409));
                    }
                    // presignDownload rejects foreign-tenant keys (1311 / defence-in-depth).
                    String url = fileStorage.presignDownload(
                            contract.getTenantId(),
                            contract.getSignedPdfStorageRef(),
                            PRESIGN_TTL);
                    return Mono.just(new SignedPdfResponse(url));
                });
    }

    /** Minimal portal-safe presign download response. */
    public record SignedPdfResponse(String downloadUrl) {}
}
