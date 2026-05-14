package com.kumouri.kmodigipresbe.controller.audit;

import com.kumouri.kmodigipresbe.audit.AuditEventDTO;
import com.kumouri.kmodigipresbe.audit.AuditQueryService;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.util.RequestMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only admin endpoints for the audit log. Admin-gated via {@link RoleGuard};
 * tenant-scoped via the repository layer.
 */
@RestController
@RequestMapping("/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditQueryService service;
    private final RequestMapper mapper;

    @GetMapping
    public Flux<AuditEventDTO> list(
            @RequestParam String entityType,
            @RequestParam UUID entityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "100") int limit) {
        return RoleGuard.requireRole("ADMIN")
                .thenMany(service.findForEntity(entityType, entityId, from, to, limit))
                .map(mapper::toAuditEventDTO);
    }

    @GetMapping("/by-actor/{userId}")
    public Flux<AuditEventDTO> byActor(
            @PathVariable UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "100") int limit) {
        return RoleGuard.requireRole("ADMIN")
                .thenMany(service.findByActor(userId, from, to, limit))
                .map(mapper::toAuditEventDTO);
    }
}
