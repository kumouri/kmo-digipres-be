package com.kumouri.kmodigipresbe.service.portal;

import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.response.PortalQuoteSummary;
import com.kumouri.kmodigipresbe.repository.QuoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Portal list service for quotes (Phase G — G.3, G-D9).
 *
 * <p>Dispatches to the correct {@link QuoteRepository} finder based on whether the
 * caller's {@link Contact} has a {@code companyId}. When {@code companyId != null},
 * the {@code $or}-based query is used so that both directly-linked and company-linked
 * quotes are returned. When {@code companyId == null}, the contactId-only derived
 * finder is used to avoid the {@code {companyId: null}} predicate trap in the
 * {@code $or}-based query.
 *
 * <p>Returns {@link PortalQuoteSummary} projections only — never raw
 * {@link com.kumouri.kmodigipresbe.model.quote.Quote} entities (G-D1).
 * The {@link com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository}
 * marker does NOT auto-scope derived finders; every query carries an explicit
 * {@code tenantId} argument (G-D9 mandatory convention).
 */
@Service
@RequiredArgsConstructor
public class PortalQuotesService {

    private final QuoteRepository quotes;

    public Flux<PortalQuoteSummary> listForContact(Contact contact) {
        UUID tenantId = contact.getTenantId();
        UUID contactId = contact.getId();
        UUID companyId = contact.getCompanyId();
        if (companyId != null) {
            return quotes.findAllByTenantAndContactOrCompany(
                            tenantId, contactId, companyId)
                    .map(PortalQuoteSummary::from);
        }
        return quotes.findAllByTenantIdAndContactIdOrderByCreatedAtDesc(
                        tenantId, contactId)
                .map(PortalQuoteSummary::from);
    }
}
