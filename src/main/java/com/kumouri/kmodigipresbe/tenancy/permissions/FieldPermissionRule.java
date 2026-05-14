package com.kumouri.kmodigipresbe.tenancy.permissions;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One field-level permission rule: a specific {@code field} on a specific
 * {@code entityType} is only included in responses for users whose role set
 * intersects {@code allowedRoles}.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class FieldPermissionRule {
    private String entityType;
    private String field;
    private List<String> allowedRoles;
}
