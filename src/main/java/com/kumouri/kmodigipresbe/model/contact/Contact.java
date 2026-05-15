package com.kumouri.kmodigipresbe.model.contact;

import com.kumouri.kmodigipresbe.audit.Auditable;
import com.kumouri.kmodigipresbe.extension.CustomFieldHost;
import com.kumouri.kmodigipresbe.model.marketing.FirstTouch;
import com.kumouri.kmodigipresbe.model.servicehub.HealthScore;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Document("contacts")
@CompoundIndex(name = "tenant_owner_idx", def = "{ 'tenantId': 1, 'ownerId': 1 }")
@CompoundIndex(name = "tenant_companyId_idx", def = "{ 'tenantId': 1, 'companyId': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Contact implements Auditable, CustomFieldHost {

    @Id
    private UUID id;

    private UUID tenantId;

    @Builder.Default
    private ContactType type = ContactType.PERSON;

    private String firstName;
    private String lastName;
    private String displayName;

    private UUID companyId;

    @Builder.Default
    private List<EmailContact> emails = List.of();

    @Builder.Default
    private List<PhoneNumber> phones = List.of();

    @Builder.Default
    private List<PostalAddress> addresses = List.of();

    @Builder.Default
    private Set<String> tags = Set.of();

    // Newsletter / list-membership topics this Contact has opted into. Distinct from
    // tags (which staff use for arbitrary CRM segmentation) — these are caller-
    // declared subscriptions originating from public lead-capture surfaces. Default
    // empty; merged additively by the newsletter subscribe endpoint.
    @Builder.Default
    private Set<String> subscriptionTopics = Set.of();

    private UUID ownerId;

    /** Null until nightly health-score compute runs. */
    private HealthScore healthScore;

    @Builder.Default
    private Map<String, Object> customFields = Map.of();

    /**
     * First marketing touch point. Set once on first form submission or landing-page
     * lead capture; never overwritten (first-touch attribution model).
     */
    private FirstTouch firstTouch;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
