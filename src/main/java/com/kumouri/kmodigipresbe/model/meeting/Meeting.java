package com.kumouri.kmodigipresbe.model.meeting;

import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Document("meetings")
@CompoundIndex(name = "tenant_start_idx", def = "{ 'tenantId': 1, 'start': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Meeting implements TenantScoped {
    @Id
    private UUID id;

    @Indexed
    private UUID tenantId;

    private String name;
    private String description;
    private String location;
    private LocalDateTime start;
    private LocalDateTime end;
    private boolean allDay;

    private UUID organizerContactId;
    private Set<UUID> attendeeContactIds;

    /**
     * Cal.com booking uid — the projection key used by {@code CalComWebhookService}
     * to upsert/cancel this Meeting in response to a verified Cal.com webhook (Phase H
     * — H.2 / H-D2 source-of-truth ADR).
     *
     * <p>Additive-nullable: legacy Meeting documents deserialize this as {@code null}
     * (the Phase-E {@code paymentTerms} precedent — no {@code @Builder.Default}; null
     * means "not sourced from Cal.com").
     *
     * <p>Sparse index: the index covers only documents where the field is present (the
     * overwhelming majority of Meeting rows pre-Cal.com will not carry this field).
     */
    @Indexed(sparse = true)
    private String calComBookingUid;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
