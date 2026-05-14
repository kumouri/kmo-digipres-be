package com.kumouri.kmodigipresbe.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AuditEventDTO {
    private UUID id;
    private UUID actorUserId;
    private String entityType;
    private UUID entityId;
    private AuditOp op;
    private List<FieldDiff> fieldDiffs;
    private Instant at;
    private String requestId;
}
