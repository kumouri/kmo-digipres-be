package com.kumouri.kmodigipresbe.controller.compliance;

import com.kumouri.kmodigipresbe.model.compliance.DataSubjectRequest;
import com.kumouri.kmodigipresbe.service.compliance.DataSubjectRequestService;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Phase 11d — GDPR Data Subject Request endpoints.
 *
 * <ul>
 *   <li>{@code POST /admin/dsr/{contactId}/export} — starts an async export job;
 *       returns 202 with the job record.</li>
 *   <li>{@code POST /admin/dsr/{contactId}/redact} — starts an async PII-redaction
 *       job; returns 202.</li>
 *   <li>{@code GET /admin/dsr/{jobId}/status} — polls job completion status.
 *       403 if the job belongs to a different tenant.</li>
 * </ul>
 *
 * <p>All endpoints are admin-only — these paths are under {@code /admin/**}, which the
 * central {@link com.kumouri.kmodigipresbe.tenancy.StaffAuthorizationWebFilter} gates on
 * the {@code ADMIN} role (security fix BE-02; the earlier claim that this was enforced in
 * {@code SecurityConfig} was false — that control did not exist until the filter was
 * added). Cross-tenant contact access returns 403 (error 3102).
 */
@RestController
@RequestMapping("/admin/dsr")
@RequiredArgsConstructor
public class DataSubjectRequestController {

    private final DataSubjectRequestService dsrService;

    @PostMapping("/{contactId}/export")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<DataSubjectRequest> export(@PathVariable UUID contactId) {
        return TenantContextHolder.required()
                .flatMap(ctx -> dsrService.submitExport(ctx.tenantId(), contactId, ctx.userId()));
    }

    @PostMapping("/{contactId}/redact")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<DataSubjectRequest> redact(@PathVariable UUID contactId) {
        return TenantContextHolder.required()
                .flatMap(ctx -> dsrService.submitRedact(ctx.tenantId(), contactId, ctx.userId()));
    }

    @GetMapping("/{jobId}/status")
    public Mono<DataSubjectRequest> status(@PathVariable UUID jobId) {
        return TenantContextHolder.required()
                .flatMap(ctx -> dsrService.getStatus(ctx.tenantId(), jobId));
    }
}
