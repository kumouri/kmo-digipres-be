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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Document("salon_bookings")
@CompoundIndex(name = "tenant_contact_idx", def = "{ 'tenantId': 1, 'contactId': 1 }")
@CompoundIndex(name = "tenant_staff_start_idx", def = "{ 'tenantId': 1, 'staffMemberId': 1, 'scheduledStart': 1 }")
@CompoundIndex(name = "tenant_status_idx", def = "{ 'tenantId': 1, 'status': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Booking implements Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    private UUID contactId;

    private UUID staffMemberId;

    /** Stable key of the {@link ServiceMenuItem} within the menu. */
    private String serviceMenuItemId;

    /** Snapshot of the service name at booking time to preserve history. */
    private String serviceMenuItemName;

    private Instant scheduledStart;

    private Instant scheduledEnd;

    @Builder.Default
    private BookingStatus status = BookingStatus.PENDING_DEPOSIT;

    private boolean depositRequired;

    private BigDecimal depositAmount;

    /** Invoice created for the deposit; null if no deposit required. */
    private UUID depositInvoiceId;

    @Builder.Default
    private boolean depositPaid = false;

    private String cancellationPolicy;

    private String notes;

    /**
     * The {@link LoyaltyAccount} to credit when this booking is completed.
     * Resolved from {@code contactId} at booking time and stored for fast lookup.
     */
    private UUID loyaltyAccountId;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Override
    public String getAuditEntityType() {
        return "BOOKING";
    }
}
