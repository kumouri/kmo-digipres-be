package com.kumouri.kmodigipresbe.tenancy.permissions;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.extension.EntityType;
import com.kumouri.kmodigipresbe.extension.FieldDefinition;
import com.kumouri.kmodigipresbe.extension.FieldDefinitionRepository;
import com.kumouri.kmodigipresbe.extension.FieldType;
import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import com.kumouri.kmodigipresbe.repository.FieldPermissionPolicyRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies FieldPermissionRedactor:
 * <ol>
 *   <li>STAFF reading a Deal with an ADMIN-only restriction on {@code value}
 *       gets a redacted map with {@code value} absent (not null) — criterion 8.</li>
 *   <li>ADMIN reading the same Deal gets the full map including {@code value}.</li>
 *   <li>Custom fields with {@code visibilityRoles} are redacted likewise.</li>
 * </ol>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FieldPermissionRedactorIT {

    @Autowired FieldPermissionRedactor redactor;
    @Autowired FieldPermissionPolicyRepository policies;
    @Autowired FieldDefinitionRepository fieldDefinitions;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;

    @BeforeEach
    void setup() {
        mongo.remove(new Query(), FieldPermissionPolicy.class).block();
        mongo.remove(new Query(), FieldDefinition.class).block();
        tenantId = UUID.randomUUID();
    }

    @Test
    void staffSeesDealWithoutValue_whenPolicyRestrictsValueToAdmin() {
        seedPolicy(FieldPermissionRule.builder()
                .entityType("Deal")
                .field("value")
                .allowedRoles(List.of("ADMIN"))
                .build());

        TenantContext staff = new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));

        Deal deal = Deal.builder()
                .tenantId(tenantId).title("Acme").stage(PipelineStage.WON)
                .value(new BigDecimal("12000.00")).currency("USD").build();

        Map<String, Object> redacted = redactor.redact(deal, "Deal")
                .contextWrite(TenantContextHolder.write(staff))
                .block();

        assertThat(redacted).isNotNull();
        assertThat(redacted).doesNotContainKey("value");
        assertThat(redacted).containsEntry("title", "Acme");
    }

    @Test
    void adminSeesValue() {
        seedPolicy(FieldPermissionRule.builder()
                .entityType("Deal")
                .field("value")
                .allowedRoles(List.of("ADMIN"))
                .build());

        TenantContext admin = new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF", "ADMIN"));

        Deal deal = Deal.builder()
                .tenantId(tenantId).title("Acme").stage(PipelineStage.WON)
                .value(new BigDecimal("12000.00")).currency("USD").build();

        Map<String, Object> redacted = redactor.redact(deal, "Deal")
                .contextWrite(TenantContextHolder.write(admin))
                .block();

        assertThat(redacted).isNotNull();
        assertThat(redacted).containsEntry("value", new BigDecimal("12000.00"));
    }

    @Test
    void customFieldRedactedByVisibilityRoles() {
        // Define a custom field "internalScore" on CONTACT, ADMIN-only.
        fieldDefinitions.save(FieldDefinition.builder()
                        .tenantId(tenantId)
                        .entityType(EntityType.CONTACT)
                        .key("internalScore")
                        .label("Internal Score")
                        .type(FieldType.NUMBER)
                        .visibilityRoles(List.of("ADMIN"))
                        .build())
                .block();

        TenantContext staff = new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));

        com.kumouri.kmodigipresbe.model.contact.Contact contact =
                com.kumouri.kmodigipresbe.model.contact.Contact.builder()
                        .tenantId(tenantId)
                        .firstName("Alice")
                        .customFields(Map.of("internalScore", 92, "tier", "GOLD"))
                        .build();

        Map<String, Object> redacted = redactor.redact(contact, EntityType.CONTACT)
                .contextWrite(TenantContextHolder.write(staff))
                .block();

        assertThat(redacted).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> custom = (Map<String, Object>) redacted.get("customFields");
        assertThat(custom).doesNotContainKey("internalScore");
        assertThat(custom).containsEntry("tier", "GOLD");
    }

    private void seedPolicy(FieldPermissionRule rule) {
        policies.save(FieldPermissionPolicy.builder()
                        .tenantId(tenantId)
                        .rules(List.of(rule))
                        .build())
                .block();
    }
}
