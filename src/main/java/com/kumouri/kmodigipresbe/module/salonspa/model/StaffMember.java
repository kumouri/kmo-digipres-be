package com.kumouri.kmodigipresbe.module.salonspa.model;

import com.kumouri.kmodigipresbe.audit.Auditable;
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
import java.util.UUID;

@Document("salon_staff")
@CompoundIndex(name = "tenant_active_idx", def = "{ 'tenantId': 1, 'active': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class StaffMember implements Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    /**
     * Links to the CRM {@code User} record when the staff member has portal/staff access.
     * May be null for staff members that only appear in scheduling but don't log in.
     */
    private UUID userId;

    private String displayName;

    /**
     * {@link ServiceMenuItem#getId()} values the staff member is certified to perform.
     * Empty = eligible for any service.
     */
    @Builder.Default
    private List<String> eligibleServiceIds = List.of();

    @Builder.Default
    private List<AvailabilityWindow> availabilityWindows = List.of();

    @Builder.Default
    private boolean active = true;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Override
    public String getAuditEntityType() {
        return "STAFF_MEMBER";
    }
}
