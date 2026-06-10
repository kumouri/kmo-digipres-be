package com.kumouri.kmodigipresbe.module.techcopilot.model;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tech Copilot (T13) — the usefulness-signal log for one tech question → answer. Persisted on every
 * {@code TechCopilotService.ask} (grounded or handoff), so the dashboard can show recent Q&amp;A and a
 * tech can mark whether the answer was {@link #helpful} (the feedback / usage leg).
 *
 * <p>{@code TenantScoped} for isolation; not {@code Auditable} (a content/log row — the
 * {@code ListingDisclosure}/{@code ReplyLogEntry} rationale). Compound index {@code {tenantId, createdAt}}
 * for the newest-first dashboard read.
 */
@Document("tech_queries")
@CompoundIndex(name = "tenant_created_idx", def = "{ 'tenantId': 1, 'createdAt': -1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TechQuery implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    /** The technician's question. */
    private String question;

    /** Optional equipment hint the tech supplied (a UI label; not a retrieval restriction). */
    private EquipmentType equipmentTypeHint;

    /** The grounded answer text; {@code null} when {@link #handoff} (nothing to ground on / model handoff). */
    private String answer;

    /** True when there was nothing to ground on, or the model could not answer from the manuals. */
    @Builder.Default
    private boolean handoff = false;

    /** The doc(s) the answer was grounded in (empty on a handoff) — deduped by {@code techDocId}. */
    @Builder.Default
    private List<QueryCitation> citations = new ArrayList<>();

    /**
     * Tech feedback on usefulness: {@code true}=helpful, {@code false}=not, {@code null}=not yet rated. The
     * usefulness signal for the corpus-quality dashboard.
     */
    private Boolean helpful;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    /** One cited source doc, carried through from the RAG chunk's metadata (T13-D5). */
    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QueryCitation {
        private UUID techDocId;
        private String techDocTitle;
        private String equipmentType;
        private String contentPreview;
        private double score;
    }
}
