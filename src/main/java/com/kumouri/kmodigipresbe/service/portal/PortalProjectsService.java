package com.kumouri.kmodigipresbe.service.portal;

import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.response.PortalProjectSummary;
import com.kumouri.kmodigipresbe.repository.project.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Portal list service for projects (Phase G — G.3, G-D9).
 *
 * <p>Dispatches to the correct {@link ProjectRepository} finder based on whether the
 * caller's {@link Contact} has a {@code companyId}. When {@code companyId != null},
 * the {@code $or}-based query is used so that both directly-linked and company-linked
 * projects are returned. When {@code companyId == null}, the contactId-only derived
 * finder is used to avoid the {@code {companyId: null}} predicate trap in the
 * {@code $or}-based query.
 *
 * <p>Returns {@link PortalProjectSummary} projections only — never raw
 * {@link com.kumouri.kmodigipresbe.model.project.Project} entities (G-D1).
 * The {@link com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository}
 * marker does NOT auto-scope derived finders; every query carries an explicit
 * {@code tenantId} argument (G-D9 mandatory convention).
 */
@Service
@RequiredArgsConstructor
public class PortalProjectsService {

    private final ProjectRepository projects;

    public Flux<PortalProjectSummary> listForContact(Contact contact) {
        UUID tenantId = contact.getTenantId();
        UUID contactId = contact.getId();
        UUID companyId = contact.getCompanyId();
        if (companyId != null) {
            return projects.findAllByTenantAndPrimaryContactOrCompany(
                            tenantId, contactId, companyId)
                    .map(PortalProjectSummary::from);
        }
        return projects.findAllByTenantIdAndPrimaryContactIdOrderByCreatedAtDesc(
                        tenantId, contactId)
                .map(PortalProjectSummary::from);
    }
}
