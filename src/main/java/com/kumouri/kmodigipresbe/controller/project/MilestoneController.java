package com.kumouri.kmodigipresbe.controller.project;

import com.kumouri.kmodigipresbe.model.idempotency.IdempotentRoute;
import com.kumouri.kmodigipresbe.model.project.Milestone;
import com.kumouri.kmodigipresbe.model.project.Milestone.MilestoneStatus;
import com.kumouri.kmodigipresbe.service.project.MilestoneService;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * REST API for {@link Milestone} sub-resources of a {@link com.kumouri.kmodigipresbe.model.project.Project}.
 *
 * <p>{@code POST /milestones/{id}/transition} is annotated with {@link IdempotentRoute}
 * because when {@code status=COMPLETED} it may spawn a DRAFT invoice (external side-effect).
 * The domain-level idempotency is the {@code spawnedInvoiceId} boolean guard in
 * {@link MilestoneService}; the annotation is the belt-and-suspenders (C-D9).
 */
@RestController
@RequestMapping("/milestones")
@ConditionalOnProperty(prefix = "kmosf.modules.projects", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class MilestoneController {

    private final MilestoneService service;

    @GetMapping("/by-project/{projectId}")
    public Flux<Milestone> listByProject(@PathVariable UUID projectId) {
        return service.findByProject(projectId);
    }

    @GetMapping("/{id}")
    public Mono<Milestone> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping("/by-project/{projectId}")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Milestone> create(@PathVariable UUID projectId, @RequestBody Milestone body) {
        return service.create(projectId, body);
    }

    @PutMapping("/{id}")
    public Mono<Milestone> update(@PathVariable UUID id, @RequestBody Milestone body) {
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return RoleGuard.requireRole("ADMIN").then(service.delete(id));
    }

    /**
     * Status transition (e.g. → COMPLETED triggers optional invoice spawn).
     * Annotated with {@link IdempotentRoute} per C-D9 (external side-effect when → COMPLETED).
     */
    @PostMapping("/{id}/transition")
    @IdempotentRoute
    public Mono<Milestone> transition(@PathVariable UUID id, @RequestParam MilestoneStatus status) {
        return service.transition(id, status);
    }
}
