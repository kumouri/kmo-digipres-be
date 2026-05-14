package com.kumouri.kmodigipresbe.model.imports;

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
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Document("import_jobs")
@CompoundIndex(name = "tenant_status_idx", def = "{ 'tenantId': 1, 'status': 1, 'createdAt': -1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ImportJob implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    /**
     * What we're importing — for Phase 4, only {@code CONTACTS} is wired. Future
     * passes add deals, companies, etc.
     */
    private String entityType;

    private ImportStatus status;

    private long totalRows;
    private long succeededRows;
    private long skippedRows;
    private long failedRows;

    @Builder.Default
    private List<ImportError> errors = List.of();

    private Instant startedAt;
    private Instant completedAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum ImportStatus { PENDING, RUNNING, SUCCEEDED, PARTIAL, FAILED }

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportError {
        private long rowNumber;
        private String reason;
        private String externalId;
    }
}
