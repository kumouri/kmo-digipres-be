package com.kumouri.kmodigipresbe.model.marketing;

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

/**
 * A tenant-branded landing page rendered at
 * {@code /public/p/{tenantSlug}/{slug}}. The {@code mustacheTemplate} field
 * holds the raw Mustache HTML; {@code formId} optionally embeds a form widget
 * token into the rendered output so visitors can submit directly without leaving
 * the page.
 *
 * <p>A page is publicly visible only when {@code publishedAt} is non-null and in
 * the past. Unpublished pages return 404 to anonymous callers.
 */
@Document("landing_pages")
@CompoundIndex(name = "tenant_slug_idx", def = "{ 'tenantId': 1, 'slug': 1 }", unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class LandingPage implements Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    /** URL-safe slug (lowercase, hyphens). Unique within the tenant. */
    private String slug;

    private String title;

    /** Mustache template HTML. Variables: {@code {{formWidgetToken}}}, {@code {{pageTitle}}}. */
    private String mustacheTemplate;

    /**
     * Optional form to embed. When set, {@code LandingPageRenderService} issues a
     * {@code widgetType="form"} token and injects it into the template as
     * {@code {{formWidgetToken}}}.
     */
    private UUID formId;

    /**
     * When non-null and not in the future, the page is publicly accessible.
     * Set to {@code Instant.now()} to publish immediately.
     */
    private Instant publishedAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Override
    public String getAuditEntityType() {
        return "LANDING_PAGE";
    }
}
