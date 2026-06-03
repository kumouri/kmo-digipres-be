package com.kumouri.kmodigipresbe.controller.integration;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.moletripwire.MoleTripwireTokenService;
import com.kumouri.kmodigipresbe.repository.project.ProjectRepository;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Admin endpoint to mint a per-customer <strong>mole-tripwire</strong> token for a given
 * coverage-customer {@code Project} (Phase 3 — NMM coverage-window automation, the B2 re-activity
 * tripwire). Rob texts the resulting tokenized link to an existing coverage customer; the customer
 * uses it to report suspected new mole activity by photo, and a high-confidence mole auto-creates a
 * re-treatment {@code Milestone} on that exact Project. The token carries the Project id (and tenant)
 * — so the Project is resolved from the token, never the public payload.
 *
 * <p>Mirrors the home-services {@code ServiceRequestTokenIssuer} (a typed token carrying entity
 * context, ADMIN-gated): {@code POST /integrations/mole-tripwire/tokens/{projectId}} →
 * {@code {"token": "..."}}. Gated:
 * <ul>
 *   <li>{@code @ConditionalOnProperty(prefix="kmosf.modules.mole-tripwire", name="enabled",
 *       matchIfMissing=true)} — same module gate as {@link MoleTripwireController} (a disabled
 *       module → endpoint not registered → 404, the Phase-1/2 precedent);</li>
 *   <li>{@link RoleGuard#requireRole "ADMIN"} — only tenant admins mint tokens ({@code 1800} if
 *       not);</li>
 *   <li>the Project is loaded tenant-scoped first — a token is only issued for a Project that
 *       actually exists for the caller's tenant ({@code 4016} otherwise).</li>
 * </ul>
 */
@RestController
@RequestMapping("/integrations/mole-tripwire/tokens")
@ConditionalOnProperty(prefix = "kmosf.modules.mole-tripwire", name = "enabled",
        matchIfMissing = true)
@RequiredArgsConstructor
public class MoleTripwireTokenController {

    /** Tripwire tokens default to 180 days — roughly NMM's 6-month coverage window. */
    public static final Duration TOKEN_TTL = Duration.ofDays(180);

    private final MoleTripwireTokenService tokenService;
    private final ProjectRepository projects;

    @PostMapping("/{projectId}")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, String>> issue(@PathVariable UUID projectId) {
        return RoleGuard.requireRole("ADMIN")
                .then(TenantContextHolder.required())
                .flatMap(ctx -> projects.findByTenantIdAndId(ctx.tenantId(), projectId)
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Project not found for tripwire-token issuance", 4016, 404)))
                        .map(project -> {
                            String token = tokenService.issue(
                                    ctx.tenantId(), project.getId(), TOKEN_TTL);
                            return Map.of("token", token);
                        }));
    }
}
