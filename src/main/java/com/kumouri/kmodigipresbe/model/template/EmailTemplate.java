package com.kumouri.kmodigipresbe.model.template;

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
import java.util.UUID;

/**
 * Tenant-defined email template. {@code subject} and {@code body} are Mustache —
 * {@code {{firstName}}} is the simplest substitution; sections / inverted sections /
 * partials all work via jmustache. {@code from} is optional; falls back to the
 * tenant's configured sender address at send time.
 *
 * <p>{@code name} is unique per tenant so the FE can refer to templates by name
 * without ambiguity.
 */
@Document("email_templates")
@CompoundIndex(name = "tenant_name_idx", def = "{ 'tenantId': 1, 'name': 1 }", unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class EmailTemplate implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    private String name;
    private String description;

    private String fromAddress;

    private String subject;
    private String body;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
