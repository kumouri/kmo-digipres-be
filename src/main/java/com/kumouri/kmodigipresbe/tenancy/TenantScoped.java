package com.kumouri.kmodigipresbe.tenancy;

import java.util.UUID;

public interface TenantScoped {
    UUID getTenantId();

    void setTenantId(UUID tenantId);
}
