package com.kumouri.kmodigipresbe.tenancy;

import java.util.Set;
import java.util.UUID;

public record TenantContext(UUID tenantId, UUID userId, Set<String> roles) {

    public static final String CONTEXT_KEY = "kmosf.tenantContext";

    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }
}
