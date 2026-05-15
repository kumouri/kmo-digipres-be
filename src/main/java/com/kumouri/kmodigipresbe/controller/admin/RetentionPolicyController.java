package com.kumouri.kmodigipresbe.controller.admin;

import com.kumouri.kmodigipresbe.automation.RuleCondition;
import com.kumouri.kmodigipresbe.model.compliance.RetentionPolicy;
import com.kumouri.kmodigipresbe.service.compliance.RetentionPolicyService;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/admin/retention-policies")
@RequiredArgsConstructor
public class RetentionPolicyController {

    private final RetentionPolicyService retentionPolicyService;

    public record UpsertRequest(int retentionDays, List<RuleCondition> exceptions) {}

    @GetMapping
    public Flux<RetentionPolicy> list() {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> retentionPolicyService.listPolicies(ctx.tenantId()));
    }

    @PutMapping("/{entityType}")
    @ResponseStatus(HttpStatus.OK)
    public Mono<RetentionPolicy> upsert(@PathVariable String entityType,
                                        @RequestBody UpsertRequest body) {
        return TenantContextHolder.required()
                .flatMap(ctx -> retentionPolicyService.upsertPolicy(
                        ctx.tenantId(), entityType,
                        body.retentionDays(), body.exceptions()));
    }
}
