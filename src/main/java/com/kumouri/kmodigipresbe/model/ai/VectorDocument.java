package com.kumouri.kmodigipresbe.model.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Stores an embedding vector alongside its source entity reference.
 *
 * <p>The Atlas Vector Search index ({@code vector_cosine_idx}) is configured
 * out-of-band on the {@code vector} field with:
 * <ul>
 *   <li>type: {@code vector}, dimensions: 1536, similarity: {@code cosine}</li>
 *   <li>filter: {@code { tenantId: 1 }} so the pre-filter is index-accelerated</li>
 * </ul>
 *
 * <p>{@code tenantId} is the leading compound-index field so that the mandatory
 * {@code $vectorSearch} pre-filter never degrades to a full-collection scan.
 */
@Document("vector_index")
@CompoundIndex(name = "tenant_source_idx",
        def = "{ 'tenantId': 1, 'sourceType': 1, 'sourceId': 1 }", unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class VectorDocument implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    /**
     * Stable entity-type label — {@code "Activity"}, {@code "Attachment"},
     * {@code "Quote"}, {@code "InboxMessage"}.
     */
    private String sourceType;

    private UUID sourceId;

    /** Dense embedding vector (1536 dimensions for text-embedding-3-small). */
    private float[] vector;

    /**
     * Display data stored alongside the vector for citation rendering.
     * Common keys: {@code contentPreview} (≤ 500 chars), {@code title}.
     */
    private Map<String, Object> metadata;

    @LastModifiedDate
    private Instant indexedAt;
}
