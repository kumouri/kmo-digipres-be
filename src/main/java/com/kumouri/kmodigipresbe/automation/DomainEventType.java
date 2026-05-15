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

    // Phase 9g — fired by MentionResolver after parsing @username from an
    // activity's summary/body and resolving to a User. MentionNotificationListener
    // subscribes to fan out a notification email.
    public static final String MENTION_CREATED = "mention.created";

    // Phase 10c — fired by the home-services EquipmentService warranty scan when
    // a piece of equipment's warranty is set to expire inside the next 30 days.
    // Sequence engine / outbound rules subscribe to send a renewal nudge. Payload:
    // {equipmentId, jobSiteId, warrantyExpiresAt, manufacturer, model, serial}.
    public static final String EQUIPMENT_WARRANTY_EXPIRING = "equipment.warrantyExpiring";

    // Phase 10d — emitted by InvoiceService.finalize when a DRAFT Invoice
    // transitions to SENT. QuickBooksInvoiceSync subscribes and pushes the
    // invoice into QBO; downstream automations can also key off this event.
    // Payload: {invoiceId, totalAmount, contactId, currency}.
    public static final String INVOICE_FINALIZED = "invoice.finalized";

    // Phase 14 — fired by CateringOrderService when a quote is issued or an order
    // is confirmed. WorkflowRule automations subscribe to send follow-up sequences.
    // Payload for QUOTE_ISSUED: {quoteId, contactId, headcount}.
    // Payload for CONFIRMED: {contactId, headcount}.
    public static final String CATERING_ORDER_QUOTE_ISSUED = "cateringOrder.quoteIssued";
    public static final String CATERING_ORDER_CONFIRMED = "cateringOrder.confirmed";

    // Phase 14 — fired by ReservationService.create() for both staff-created and
    // widget-submitted reservations. Payload: {contactId, partySize}.
    public static final String RESERVATION_CREATED = "reservation.created";

    // Phase 10e — fired by WorkOrderService.update() when a work order transitions
    // INTO EN_ROUTE (not on save-with-no-change, not when previous was already
    // EN_ROUTE). The seeded "on-the-way-sms-default" WorkflowRule subscribes and
    // dispatches an SMS via the SEND_SMS action. Payload includes:
    // {workOrderId, jobSiteId, technicianUserId, contactPhoneE164} — contactPhoneE164
    // is resolved by joining JobSite -> contactId -> Contact.phones; null when the
    // contact has no E.164 number, in which case the dispatcher skips the send.
    public static final String WORK_ORDER_EN_ROUTE = "workOrder.enRoute";

    // Phase 12b — fired by SalonBookingService.complete() when a salon booking
    // transitions to COMPLETED. Phase 12c LoyaltyAccrualService and
    // RebookingNudgeService subscribe. Payload:
    // {bookingId, contactId, staffMemberId, loyaltyAccountId, serviceMenuItemId}.
    public static final String BOOKING_COMPLETED = "booking.completed";

    // Phase 12c — fired when a Payment record is marked as capturing an invoice
    // in full (sum(payments) >= invoice.total). LoyaltyAccrualService subscribes
    // to credit points on salon bookings that required a deposit. Payload:
    // {invoiceId, contactId, amount, currency}.
    public static final String INVOICE_PAID = "invoice.paid";

    // Phase 12e — fired by FormSubmissionService after a public form submission
    // is validated and the Contact is upserted. Sequence engine subscribes for
    // auto-enrollment; UtmCaptureService writes FirstTouch before this event fires.
    // Payload: {formId, submissionId, contactId, utmSource, utmCampaign}.
    public static final String FORM_SUBMITTED = "form.submitted";

    // Phase 12c — fired by LoyaltyAccrualService when a LoyaltyAccount crosses a
    // tier threshold (e.g., BRONZE → SILVER). Payload:
    // {accountId, contactId, previousTier, newTier}.
    public static final String LOYALTY_TIER_UPGRADED = "loyalty.tierUpgraded";

    private DomainEventType() {
    }
}
