package com.kumouri.kmodigipresbe.model.calendar;

import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A public bookable resource — the analog of a Calendly link. {@code slug} is
 * globally unique because it's in the public URL (we don't know the tenant
 * until we resolve the slug).
 */
@Document("booking_links")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class BookingLink implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    @Indexed(unique = true)
    private String slug;

    private String title;
    private String description;

    private UUID ownerUserId;

    /**
     * Slot duration in minutes — 15, 30, 60 etc.
     */
    @Builder.Default
    private int durationMinutes = 30;

    /**
     * Minimum lead time before a slot can be booked.
     */
    @Builder.Default
    private int leadTimeMinutes = 60;

    /**
     * Buffer between back-to-back meetings.
     */
    @Builder.Default
    private int bufferMinutes = 0;

    /**
     * IANA timezone identifier used to interpret {@link #availability}.
     */
    @Builder.Default
    private String timezone = "America/Chicago";

    @Builder.Default
    private List<AvailabilityRule> availability = List.of();

    @Builder.Default
    private boolean active = true;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
