package com.kumouri.kmodigipresbe.module.homeservices.model;

import com.kumouri.kmodigipresbe.audit.Auditable;
import com.kumouri.kmodigipresbe.extension.CustomFieldHost;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Document("service_agreements")
@CompoundIndex(name = "tenant_contact_idx", def = "{ 'tenantId': 1, 'contactId': 1 }")
@CompoundIndex(name = "tenant_status_idx", def = "{ 'tenantId': 1, 'status': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ServiceAgreement implements Auditable, CustomFieldHost {

    @Id
    private UUID id;

    private UUID tenantId;

    private UUID contactId;
    private UUID jobSiteId;

    /**
     * Free-form agreement category — e.g. {@code "quarterly-hvac"},
     * {@code "monthly-bait-station"}. Tenants can constrain with a custom field
     * to enforce a controlled vocabulary.
     */
    private String agreementType;

    private LocalDate startDate;
    private LocalDate endDate;

    /**
     * RFC 5545 RRULE that drives {@code MaintenanceVisit} materialization via
     * {@code ServiceAgreementSchedulerService} (10b). The {@code startDate} is
     * the seed/anchor for {@code RecurringSchedule.expand}.
     */
    private String recurrenceRule;

    @Builder.Default
    private List<UUID> includedServices = List.of();

    private UUID priceListId;

    private BillingCadence billingCadence;

    @Builder.Default
    private ServiceAgreementStatus status = ServiceAgreementStatus.DRAFT;

    @Builder.Default
    private Map<String, Object> customFields = Map.of();

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Override
    public String getEntityType() {
        return "SERVICE_AGREEMENT";
    }
}
