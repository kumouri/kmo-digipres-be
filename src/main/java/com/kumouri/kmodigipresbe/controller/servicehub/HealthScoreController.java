package com.kumouri.kmodigipresbe.controller.servicehub;

import com.kumouri.kmodigipresbe.model.servicehub.HealthScore;
import com.kumouri.kmodigipresbe.service.servicehub.HealthScoreService;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@RestController
@ConditionalOnProperty(prefix = "kmosf.modules.service-hub", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class HealthScoreController {

    private final HealthScoreService service;

    @GetMapping("/contacts/{id}/health-score")
    public Mono<HealthScore> contactScore(@PathVariable UUID id) {
        return TenantContextHolder.required()
                .flatMap(ctx -> service.getContactScore(ctx.tenantId(), id));
    }

    @GetMapping("/companies/{id}/health-score")
    public Mono<HealthScore> companyScore(@PathVariable UUID id) {
        return TenantContextHolder.required()
                .flatMap(ctx -> service.getCompanyScore(ctx.tenantId(), id));
    }

    @PostMapping("/admin/health-score/refresh")
    public Mono<Map<String, Long>> refresh() {
        return RoleGuard.requireRole("ADMIN")
                .then(service.computeAll())
                .map(count -> Map.of("scored", count));
    }
}
