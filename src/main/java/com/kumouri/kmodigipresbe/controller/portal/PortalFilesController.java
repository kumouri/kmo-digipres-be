package com.kumouri.kmodigipresbe.controller.portal;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.response.PortalFileSummary;
import com.kumouri.kmodigipresbe.repository.AttachmentRepository;
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
 * Portal project-file surface (Phase G — G.4, G-D1, G-D9).
 *
 * <p>All data funnels through {@link PortalOwnershipGuard#requireOwnedProject} so
 * the project ownership gate always precedes any attachment lookup. No
 * {@code @ConditionalOnProperty} — the portal chain is the gate.
 *
 * <h2>Attachment convention (verified from branch)</h2>
 * Project files are {@link com.kumouri.kmodigipresbe.model.files.Attachment} rows
 * with {@code subjectType="PROJECT"} and {@code subjectId=<projectId>}. This is
 * the free-form {@code subjectType} string convention documented in
 * {@code Attachment.java} and used consistently across all verticals
 * ({@code "EXPENSE"} in Phase D, etc.). The {@code AttachmentRepository} carries an
 * index on {@code (tenantId, subjectType, subjectId, createdAt)} so the list query
 * is fully index-covered.
 *
 * <h2>Download (presign-URL only — G-D6)</h2>
 * {@code GET /projects/{id}/files/{fileId}/download} gates on project ownership,
 * then confirms the attachment belongs to the exact project (same
 * {@code tenantId} + {@code subjectType="PROJECT"} + {@code subjectId=projectId}).
 * Returns {@code 3807 / 404} when the file is not found, wrong tenant, or not
 * linked to the requested project. The raw {@code storageRef} is never exposed.
 * The foreign-tenant key guard ({@code 1311}) in
 * {@link FileStorageService#presignDownload} acts as defence-in-depth.
 */
@RestController
@RequestMapping("/portal/me")
@RequiredArgsConstructor
public class PortalFilesController {

    private static final String PROJECT_SUBJECT_TYPE = "PROJECT";
    private static final Duration PRESIGN_TTL = Duration.ofMinutes(15);

    private final PortalOwnershipGuard ownershipGuard;
    private final AttachmentRepository attachments;
    private final FileStorageService fileStorage;

    /**
     * Lists all tenant-scoped attachments for the caller's owned project.
     * Ownership gate precedes the attachment query.
     */
    @GetMapping("/projects/{id}/files")
    public Flux<PortalFileSummary> listProjectFiles(@PathVariable UUID id) {
        return ownershipGuard.requireOwnedProject(id)
                .flatMapMany(project -> attachments
                        .findAllByTenantIdAndSubjectTypeAndSubjectIdOrderByCreatedAtDesc(
                                project.getTenantId(),
                                PROJECT_SUBJECT_TYPE,
                                project.getId()))
                .map(PortalFileSummary::from);
    }

    /**
     * Returns a presigned download URL for a specific file under an owned project.
     * Rejects with {@code 3807 / 404} when the attachment does not exist, belongs to a
     * different tenant, or is not linked to the requested project. Presign-URL only —
     * no bytes stream through the BE (G-D6 hard line).
     */
    @GetMapping("/projects/{id}/files/{fileId}/download")
    public Mono<FileDownloadResponse> downloadProjectFile(
            @PathVariable UUID id,
            @PathVariable UUID fileId) {
        return ownershipGuard.requireOwnedProject(id)
                .flatMap(project ->
                        attachments.findById(fileId)
                                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                        "File not found", 3807, 404)))
                                .flatMap(attachment -> {
                                    // Confirm the attachment belongs to this tenant + project.
                                    boolean tenantMatch = project.getTenantId() != null
                                            && project.getTenantId().equals(attachment.getTenantId());
                                    boolean subjectMatch = PROJECT_SUBJECT_TYPE.equals(attachment.getSubjectType())
                                            && project.getId() != null
                                            && project.getId().equals(attachment.getSubjectId());
                                    if (!tenantMatch || !subjectMatch) {
                                        return Mono.error(new DigiPresBeException(
                                                "File not found", 3807, 404));
                                    }
                                    // presignDownload rejects foreign-tenant keys (1311 / defence-in-depth).
                                    String url = fileStorage.presignDownload(
                                            project.getTenantId(),
                                            attachment.getStorageRef(),
                                            PRESIGN_TTL);
                                    return Mono.just(new FileDownloadResponse(url));
                                }));
    }

    /** Minimal portal-safe presign download response. */
    public record FileDownloadResponse(String downloadUrl) {}
}
