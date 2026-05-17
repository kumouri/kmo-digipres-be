package com.kumouri.kmodigipresbe.service.portal;

import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.response.PortalContractSummary;
import com.kumouri.kmodigipresbe.repository.contract.ContractRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Portal list service for contracts (Phase G — G.3, G-D6, G-D9).
 *
 * <p>Dispatches to the correct {@link ContractRepository} finder based on whether the
 * caller's {@link Contact} has a {@code companyId}. When {@code companyId != null},
 * the {@code $or}-based query is used so that both directly-linked and company-linked
 * contracts are returned. When {@code companyId == null}, the contactId-only derived
 * finder is used to avoid the {@code {companyId: null}} predicate trap in the
 * {@code $or}-based query.
 *
 * <p>Returns {@link PortalContractSummary} projections only — never raw
 * {@link com.kumouri.kmodigipresbe.model.contract.Contract} entities (G-D1).
 * The {@link com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository}
 * marker does NOT auto-scope derived finders; every query carries an explicit
 * {@code tenantId} argument (G-D9 mandatory convention).
 *
 * <p>The Documenso deep-link and signed-PDF availability derivations live in
 * {@link PortalContractSummary#from} (G-D6 — see that record's Javadoc for the
 * no-live-Documenso guarantee and the FE-composes-URL contract).
 */
@Service
@RequiredArgsConstructor
public class PortalContractsService {

    private final ContractRepository contracts;

    public Flux<PortalContractSummary> listForContact(Contact contact) {
        UUID tenantId = contact.getTenantId();
        UUID contactId = contact.getId();
        UUID companyId = contact.getCompanyId();
        if (companyId != null) {
            return contracts.findAllByTenantAndContactOrCompany(
                            tenantId, contactId, companyId)
                    .map(PortalContractSummary::from);
        }
        return contracts.findAllByTenantIdAndContactIdOrderByCreatedAtDesc(
                        tenantId, contactId)
                .map(PortalContractSummary::from);
    }
}
