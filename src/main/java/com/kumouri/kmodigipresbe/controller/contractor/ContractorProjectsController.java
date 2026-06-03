package com.kumouri.kmodigipresbe.controller.contractor;

import com.kumouri.kmodigipresbe.model.contact.Company;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.response.ContractorClientView;
import com.kumouri.kmodigipresbe.model.response.ContractorProjectView;
import com.kumouri.kmodigipresbe.model.response.ContractorTaskView;
import com.kumouri.kmodigipresbe.repository.CompanyRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.contractor.ProjectAssignmentRepository;
import com.kumouri.kmodigipresbe.repository.project.ProjectRepository;
import com.kumouri.kmodigipresbe.repository.project.TaskRepository;
import com.kumouri.kmodigipresbe.service.contractor.ContractorAccessGuard;
import com.kumouri.kmodigipresbe.service.contractor.ContractorSelfResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

/**
 * Contractor-scoped projects surface (Phase J — J2), the contractor analogue of
 * {@link com.kumouri.kmodigipresbe.controller.portal.PortalProjectsController}.
 *
 * <p>Every method funnels through {@link ContractorSelfResolver} (list — self id from the
 * token, never the request) or {@link ContractorAccessGuard#requireAssignedProject} (single
 * — same-404 if not actively assigned). Projection records out, never raw entities — a
 * contractor sees their assigned projects' work fields and a minimal read-only view of the
 * linked client, nothing of the sales pipeline, finances, or full CRM.
 *
 * <p>Module-gated via {@code kmosf.modules.contractor.enabled} (on by default — the J1
 * controller precedent).
 */
@RestController
@RequestMapping("/me/contractor")
@ConditionalOnProperty(prefix = "kmosf.modules.contractor", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class ContractorProjectsController {

    private final ContractorSelfResolver self;
    private final ContractorAccessGuard guard;
    private final ProjectAssignmentRepository assignments;
    private final ProjectRepository projects;
    private final TaskRepository tasks;
    private final ContactRepository contacts;
    private final CompanyRepository companies;

    /**
     * Lists the calling contractor's actively-assigned projects. Self id is resolved from
     * the token; only {@code active} assignments are returned (J scoping).
     */
    @GetMapping("/projects")
    public Flux<ContractorProjectView> listProjects() {
        return self.resolve().flatMapMany(s ->
                assignments.findAllByTenantIdAndUserIdAndActiveTrue(s.tenantId(), s.userId())
                        .flatMap(a -> projects.findByTenantIdAndId(s.tenantId(), a.getProjectId()))
                        .map(ContractorProjectView::from));
    }

    /**
     * Returns the projection for a single assigned project. {@code 4132}/404 (same as
     * not-found) if the caller is not actively assigned.
     */
    @GetMapping("/projects/{id}")
    public Mono<ContractorProjectView> getProject(@PathVariable UUID id) {
        return guard.requireAssignedProject(id).map(ContractorProjectView::from);
    }

    /**
     * Lists the tasks within an assigned project. Gates on the assignment first; the
     * subsequent task query is tenant- and project-scoped.
     */
    @GetMapping("/projects/{id}/tasks")
    public Flux<ContractorTaskView> listTasks(@PathVariable UUID id) {
        return guard.requireAssignedProject(id).flatMapMany(project ->
                tasks.findAllByTenantIdAndProjectIdOrderByOrderIndexAsc(
                                project.getTenantId(), project.getId())
                        .map(ContractorTaskView::from));
    }

    /**
     * Returns a minimal read-only view of the assigned project's linked client — the
     * primary contact (name + primary email/phone) and the company name. Nothing else from
     * the CRM. Gates on the assignment first; loads contact/company via their tenant-scoped
     * finders. If the project has no primary contact and/or no company, the corresponding
     * fields are {@code null} but the view is still returned.
     */
    @GetMapping("/projects/{id}/client")
    public Mono<ContractorClientView> getClient(@PathVariable UUID id) {
        return guard.requireAssignedProject(id).flatMap(project -> {
            UUID tenantId = project.getTenantId();
            Mono<Optional<Contact>> contactMono = project.getPrimaryContactId() == null
                    ? Mono.just(Optional.empty())
                    : contacts.findByTenantIdAndId(tenantId, project.getPrimaryContactId())
                            .map(Optional::of).defaultIfEmpty(Optional.empty());
            Mono<Optional<Company>> companyMono = project.getCompanyId() == null
                    ? Mono.just(Optional.empty())
                    : companies.findByTenantIdAndId(tenantId, project.getCompanyId())
                            .map(Optional::of).defaultIfEmpty(Optional.empty());
            return Mono.zip(contactMono, companyMono)
                    .map(tuple -> ContractorClientView.from(
                            tuple.getT1().orElse(null), tuple.getT2().orElse(null)));
        });
    }
}
