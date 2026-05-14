package com.kumouri.kmodigipresbe.extension;

/**
 * The set of CRM entities that accept tenant-defined custom fields. Vertical
 * modules add more values here (Phase 3: WORK_ORDER, JOB_SITE) via the same
 * persisted string; this enum is a convenience for the core entities.
 */
public final class EntityType {
    public static final String CONTACT = "CONTACT";
    public static final String COMPANY = "COMPANY";
    public static final String DEAL = "DEAL";
    public static final String ACTIVITY = "ACTIVITY";

    private EntityType() {
    }
}
