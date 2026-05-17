package com.kumouri.kmodigipresbe.model.response;

import com.kumouri.kmodigipresbe.model.contract.Contract;
import com.kumouri.kmodigipresbe.model.contract.ContractTemplate;

import java.time.Instant;

/**
 * Portal-facing contract projection (Phase G — G-D1, G-D6).
 *
 * <p>Omits {@code tenantId}, {@code version}, {@code templateId}, {@code parentContractId},
 * {@code dealId}, {@code contactId}, {@code companyId}, {@code quoteId},
 * {@code variables}, {@code renderedPdfStorageRef}, {@code signedPdfStorageRef} (raw),
 * {@code promotedDealToWon}, {@code spawnedProjectId}, {@code voidReason},
 * and timestamps — these are staff-internal.
 *
 * <h2>Documenso deep-link (G-D6)</h2>
 * {@link #documensoSigningDeepLink} surfaces the stored {@code documensoDocumentId} when
 * the status is {@code SENT} and a document id is available. No live Documenso call is
 * ever made (§7 hard no-live-Documenso line). There is no {@code kmosf.documenso.signing-base-url}
 * property on this branch — the FE is responsible for composing the full signing URL from
 * this correlation id. This field is {@code null} when status ≠ {@code SENT} or when the
 * document has not yet been submitted to Documenso.
 *
 * <h2>Signed PDF availability (G-D6)</h2>
 * {@link #signedPdfAvailable} is a derived boolean: {@code true} iff
 * {@code status == SIGNED && signedPdfStorageRef != null}. The client uses this flag to
 * show/hide the "Download signed PDF" action without exposing the raw storage ref.
 */
public record PortalContractSummary(
        String id,
        String contractNumber,
        String title,
        ContractTemplate.Kind kind,
        Contract.Status status,
        Instant sentAt,
        Instant signedAt,
        String documensoSigningDeepLink,
        boolean signedPdfAvailable) {

    /**
     * Constructs a portal-safe projection from a {@link Contract}.
     *
     * <p>Per G-D6:
     * <ul>
     *   <li>{@code documensoSigningDeepLink} = the stored {@code documensoDocumentId}
     *       when {@code status == SENT && documensoDocumentId != null}; {@code null}
     *       otherwise. No signing-base-url property exists on this branch — the FE
     *       composes the full URL from this id.</li>
     *   <li>{@code signedPdfAvailable} = {@code status == SIGNED && signedPdfStorageRef != null}</li>
     * </ul>
     */
    public static PortalContractSummary from(Contract contract) {
        boolean isSent = contract.getStatus() == Contract.Status.SENT;
        String deepLink = (isSent && contract.getDocumensoDocumentId() != null)
                ? contract.getDocumensoDocumentId()
                : null;
        boolean pdfAvailable = contract.getStatus() == Contract.Status.SIGNED
                && contract.getSignedPdfStorageRef() != null;
        return new PortalContractSummary(
                contract.getId() == null ? null : contract.getId().toString(),
                contract.getContractNumber(),
                contract.getTitle(),
                contract.getKind(),
                contract.getStatus(),
                contract.getSentAt(),
                contract.getSignedAt(),
                deepLink,
                pdfAvailable);
    }
}
