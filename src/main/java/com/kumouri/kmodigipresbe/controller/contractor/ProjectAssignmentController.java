package com.kumouri.kmodigipresbe.controller.contractor;

import com.kumouri.kmodigipresbe.model.contractor.ProjectAssignment;
import com.kumouri.kmodigipresbe.model.idempotency.IdempotentRoute;
import com.kumouri.kmodigipresbe.service.contractor.ProjectAssignmentService;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Project assignment CRUD (Phase J) — all ADMIN-gated. {@code POST} is idempotent on
 * {@code (project, user)} → 201 first / 200 repeat (the {@code convertFromDeal} precedent),
 * plus {@link IdempotentRoute} for client-retry dedup. DELETE is a soft-unassign
 * ({@code active=false}); logged time keeps its rates.
 *
 * <p>Module-gated via {@code kmosf.modules.contractor.enabled}.
 */
@RestController
@RequestMapping("/projects/{projectId}/assignments")
@ConditionalOnProperty(prefix = "kmosf.modules.contractor", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class ProjectAssignmentController {

    private final ProjectAssignmentService service;

    @GetMapping
    public Flux<ProjectAssignment> list(@PathVariable UUID projectId) {
        return RoleGuard.requireRole("ADMIN").thenMany(service.listByProject(projectId));
    }

    @PostMapping
    @IdempotentRoute
    public Mono<ResponseEntity<ProjectAssignment>> assign(
            @PathVariable UUID projectId, @RequestBody ProjectAssignment body) {
        return RoleGuard.requireRole("ADMIN")
                .then(service.assign(projectId, body))
                .map(result -> result.created()
                        ? ResponseEntity.status(HttpStatus.CREATED).body(result.assignment())
                        : ResponseEntity.ok(result.assignment()));
    }

    @PutMapping("/{id}")
    public Mono<ProjectAssignment> update(
            @PathVariable UUID projectId, @PathVariable UUID id, @RequestBody ProjectAssignment body) {
        return RoleGuard.requireRole("ADMIN").then(service.updateAssignment(projectId, id, body));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> unassign(@PathVariable UUID projectId, @PathVariable UUID id) {
        return RoleGuard.requireRole("ADMIN").then(service.unassign(projectId, id));
    }
}
