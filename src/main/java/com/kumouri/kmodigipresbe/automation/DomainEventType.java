package com.kumouri.kmodigipresbe.automation;

/**
 * The set of domain events that workflow rules can subscribe to. Stored on
 * {@link com.kumouri.kmodigipresbe.automation.WorkflowRule#getTrigger()} as a
 * string so rules can match on subtypes (e.g. {@code DEAL_STAGE_CHANGED}) without
 * the enum needing one variant per subject.
 */
public final class DomainEventType {
    public static final String CONTACT_CREATED = "contact.created";
    public static final String CONTACT_UPDATED = "contact.updated";
    public static final String COMPANY_CREATED = "company.created";
    public static final String COMPANY_UPDATED = "company.updated";
    public static final String DEAL_CREATED = "deal.created";
    public static final String DEAL_UPDATED = "deal.updated";
    public static final String DEAL_STAGE_CHANGED = "deal.stageChanged";
    public static final String ACTIVITY_LOGGED = "activity.logged";
    public static final String EMAIL_RECEIVED = "email.received";

    // Phase 9c — outbound transactional email engagement events emitted by the
    // Postmark webhook controller. Sequence engine (9d) and contact-timeline UI
    // subscribe.
    public static final String EMAIL_DELIVERED = "email.delivered";
    public static final String EMAIL_OPENED = "email.opened";
    public static final String EMAIL_CLICKED = "email.clicked";
    public static final String EMAIL_BOUNCED = "email.bounced";
    public static final String EMAIL_SPAM = "email.spam";

    private DomainEventType() {
    }
}
