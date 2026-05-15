package com.kumouri.kmodigipresbe.model.servicehub;

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
import java.util.UUID;

@Document("tickets")
@CompoundIndex(name = "tenant_status_idx", def = "{'tenantId':1,'status':1}")
@CompoundIndex(name = "tenant_assigned_idx", def = "{'tenantId':1,'assignedUserId':1}")
@CompoundIndex(name = "tenant_sla_due_idx", def = "{'tenantId':1,'slaResolutionDue':1}")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Ticket implements Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    private UUID contactId;

    private UUID companyId;

    private String subject;

    private String body;

    @Builder.Default
    private TicketStatus status = TicketStatus.NEW;

    @Builder.Default
    private TicketPriority priority = TicketPriority.MEDIUM;

    private UUID assignedUserId;

    private UUID slaPolicyId;

    private Instant slaResponseDue;

    private Instant slaResolutionDue;

    private Instant slaBreachedAt;

    private Instant resolvedAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
