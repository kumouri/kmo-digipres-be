package com.kumouri.kmodigipresbe.module.restaurantlight.model;

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
import java.util.Map;
import java.util.UUID;

/**
 * A lightweight reservation record covering catering tastings or in-house
 * private events. Not intended for full table-management (turn times, section
 * maps, etc.) — this is a CRM-side view of the booking for contact history
 * and follow-up automation.
 *
 * <p>{@link #externalRef} carries the booking ID from the restaurant's native
 * reservation system (Tock, OpenTable, Resy) when ingested via a webhook
 * controller. Submissions via the anonymous public widget leave it null until
 * staff sync.
 */
@Document("restaurant_light_reservations")
@CompoundIndex(name = "tenant_status_reserved_idx", def = "{ 'tenantId': 1, 'status': 1, 'reservedAt': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Reservation implements Auditable, CustomFieldHost {

    @Id
    private UUID id;

    private UUID tenantId;
    private UUID contactId;

    private Instant reservedAt;
    private int partySize;

    @Builder.Default
    private ReservationStatus status = ReservationStatus.PENDING;

    private String notes;

    /** External booking ID from Tock, OpenTable, or Resy — null for widget-originated bookings. */
    private String externalRef;

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
        return "RESERVATION";
    }
}
