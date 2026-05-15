package com.kumouri.kmodigipresbe.model.servicehub;

import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Document("sla_policies")
@CompoundIndex(name = "tenant_name_uidx", def = "{'tenantId':1,'name':1}", unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SlaPolicy implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    private String name;

    /**
     * Target minutes to first response, keyed by {@link TicketPriority} name.
     * E.g., {@code {"URGENT": 30, "HIGH": 120, "MEDIUM": 480, "LOW": 1440}}.
     */
    @Builder.Default
    private Map<String, Integer> responseTargetMinutes = Map.of();

    /**
     * Target minutes to resolution, keyed by {@link TicketPriority} name.
     */
    @Builder.Default
    private Map<String, Integer> resolutionTargetMinutes = Map.of();

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
