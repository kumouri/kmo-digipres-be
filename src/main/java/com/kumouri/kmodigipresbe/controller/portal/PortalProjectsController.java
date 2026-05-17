package com.kumouri.kmodigipresbe.controller.portal;

import com.kumouri.kmodigipresbe.model.response.PortalProjectSummary;
import com.kumouri.kmodigipresbe.service.portal.PortalLinkedContactResolver;
import com.kumouri.kmodigipresbe.service.portal.PortalOwnershipGuard;
import com.kumouri.kmodigipresbe.service.portal.PortalProjectsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Portal projects surface (Phase G — G.4, G-D1, G-D9).
 *
 * <p>All data funnels through {@link PortalLinkedContactResolver} (list) or
 * {@link PortalOwnershipGuard#requireOwnedProject} (single). No
 * {@code @ConditionalOnProperty} — the portal chain + per-user {@code User.contactId}
 * is the gate (the existing portal-controller precedent). Projection records only;
 * never raw entities (G-D1).
 *
 * <p>Milestone/task summary: the {@code PortalProjectSummary} record is scoped to
 * fields cheaply available on {@link com.kumouri.kmodigipresbe.model.project.Project}
 * directly. Including milestone/task detail would require new Flux-join finders across
 * {@code MilestoneRepository} + {@code TaskRepository} (no per-project summary method
 * exists on the branch). Per the G.4 scope decision: deferred — only the project
 * summary is returned for {@code GET /projects/{id}}. No new repo finders are added
 * in G.4.
 */
@RestController
@RequestMapping("/portal/me")
@RequiredArgsConstructor
public class PortalProjectsController {

    private final PortalLinkedContactResolver linkedContact;
    private final PortalProjectsService portalProjectsService;
    private final PortalOwnershipGuard ownershipGuard;

    @GetMapping("/projects")
    public Flux<PortalProjectSummary> listProjects() {
        return linkedContact.resolve()
                .flatMapMany(portalProjectsService::listForContact);
    }

    /**
     * Returns the portal projection for a single owned project. Milestone/task
     * summary is deferred (no per-project summary finder exists on this branch —
     * see class-level Javadoc).
     */
    @GetMapping("/projects/{id}")
    public Mono<PortalProjectSummary> getProject(@PathVariable UUID id) {
        return ownershipGuard.requireOwnedProject(id)
                .map(PortalProjectSummary::from);
    }
}
