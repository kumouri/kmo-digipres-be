package com.kumouri.kmodigipresbe.module.restaurantlight.model;

import com.kumouri.kmodigipresbe.audit.Auditable;
import com.kumouri.kmodigipresbe.extension.CustomFieldHost;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Document("restaurant_light_menu_items")
@CompoundIndex(name = "tenant_course_available_idx", def = "{ 'tenantId': 1, 'course': 1, 'available': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class MenuItem implements Auditable, CustomFieldHost {

    @Id
    private UUID id;

    private UUID tenantId;

    private String name;
    private String description;
    private MenuItemCourse course;

    /** Price in the tenant's base currency, smallest unit (e.g. cents for USD). */
    private long unitPriceCents;

    /** Dietary tags, e.g. {@code "GF"} (gluten-free), {@code "VG"} (vegan), {@code "DF"} (dairy-free). */
    @Builder.Default
    private Set<String> dietaryFlags = Set.of();

    /** Free-text add-on choices offered at order time, e.g. {@code "no onions"}. */
    @Builder.Default
    private List<String> modifiers = List.of();

    @Builder.Default
    private boolean available = true;

    @Builder.Default
    private Map<String, Object> customFields = Map.of();

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Override
    public String getEntityType() {
        return "MENU_ITEM";
    }
}
