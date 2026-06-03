package com.kumouri.kmodigipresbe.controller.project;

import com.kumouri.kmodigipresbe.model.idempotency.IdempotentRoute;
import com.kumouri.kmodigipresbe.model.project.Project;
import com.kumouri.kmodigipresbe.model.project.Project.ProjectStatus;
import com.kumouri.kmodigipresbe.service.project.ProjectService;
import com.kumouri.kmodigipresbe.service.project.ProjectService.ConversionResult;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * REST API for {@link Project} resources (Phase C).
 *
 * <p>Module-gated via {@code kmosf.modules.projects.enabled} (on by default, per C-D10).
 * Raw-entity request/response bodies — no DTO/MapStruct (Ticket/Invoice convention).
 * DELETE gated via {@link RoleGuard#requireRole(String)} (C-D10).
 *
 * <p>{@code POST /projects/from-deal/{dealId}} is annotated with {@link IdempotentRoute}
 * because it has an external side-effect (creates a Project). The domain-level idempotency
 * is the {@code existsByTenantIdAndDealId} boolean guard in {@link ProjectService}; the
 * annotation is the belt-and-suspenders for client-retry deduplication (C-D9).
 */
@RestController
@RequestMapping("/projects")
@ConditionalOnProperty(prefix = "kmosf.modules.projects", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService service;

    /**
     * Broad reader (every project in the tenant) — {@code RoleGuard.denyRole("CONTRACTOR")}
     * (Phase J — J2) pushes a contractor onto their assignment-scoped
     * {@code GET /me/contractor/projects} (→ 4135). Plain STAFF (non-contractor) employees
     * are unaffected.
     */
    @GetMapping
    public Flux<Project> list() {
        return RoleGuard.denyRole("CONTRACTOR").thenMany(service.findAll());
    }

    @GetMapping("/{id}")
    public Mono<Project> get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Project> create(@RequestBody Project body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public Mono<Project> update(@PathVariable UUID id, @RequestBody Project body) {
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return RoleGuard.requireRole("ADMIN").then(service.delete(id));
    }

    @PostMapping("/{id}/status")
    public Mono<Project> setStatus(@PathVariable UUID id, @RequestParam ProjectStatus target) {
        return service.setStatus(id, target);
    }

    /**
     * One-click Deal→Project conversion (C-D6, mirroring {@code POST /invoices/from-quote/{quoteId}}).
     * Returns 201 on first call (new Project) and 200 with the existing Project on repeats (idempotent).
     * Annotated with {@link IdempotentRoute} per C-D9 (external side-effect).
     */
    @PostMapping("/from-deal/{dealId}")
    @IdempotentRoute
    public Mono<ResponseEntity<Project>> fromDeal(@PathVariable UUID dealId) {
        return service.convertFromDeal(dealId)
                .map(result -> result.created()
                        ? ResponseEntity.status(HttpStatus.CREATED).body(result.project())
                        : ResponseEntity.ok(result.project()));
    }
}
