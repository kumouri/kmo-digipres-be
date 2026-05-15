package com.kumouri.kmodigipresbe.model.servicehub;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

@Document("ticket_comments")
@CompoundIndex(name = "tenant_ticket_idx", def = "{'tenantId':1,'ticketId':1}")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TicketComment {

    @Id
    private UUID id;

    private UUID tenantId;

    private UUID ticketId;

    private UUID authorUserId;

    private String body;

    @CreatedDate
    private Instant createdAt;
}
