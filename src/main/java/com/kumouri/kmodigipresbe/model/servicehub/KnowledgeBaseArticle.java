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
import org.springframework.data.mongodb.core.index.TextIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Document("kb_articles")
@CompoundIndex(name = "tenant_slug_uidx", def = "{'tenantId':1,'slug':1}", unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBaseArticle implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    @TextIndexed(weight = 5)
    private String title;

    @TextIndexed
    private String body;

    @Builder.Default
    @TextIndexed
    private List<String> tags = List.of();

    private String slug;

    /** Null while the article is a draft; set when published. */
    private Instant publishedAt;

    /** Placeholder for Phase 11 RAG — null until EmbeddingPipeline populates it. */
    private double[] embedding;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
