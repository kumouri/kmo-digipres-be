package com.kumouri.kmodigipresbe.module.fieldservice.model;

import java.util.Set;

public enum WorkOrderStatus {
    DRAFT, SCHEDULED, EN_ROUTE, ON_SITE, COMPLETED, CANCELLED;

    private static final Set<WorkOrderStatus> ACTIVE =
            Set.of(SCHEDULED, EN_ROUTE, ON_SITE);

    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
