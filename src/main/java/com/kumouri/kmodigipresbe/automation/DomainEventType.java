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

    // Phase 11a — emitted by AttachmentService when an Attachment document is
    // saved. EmbeddingPipeline subscribes to index the filename/content preview.
    public static final String ATTACHMENT_CREATED = "attachment.created";

    // Phase 11a — emitted by QuoteService when a Quote document is saved.
    // EmbeddingPipeline subscribes to index the quote summary/line items.
    public static final String QUOTE_CREATED = "quote.created";

    // Phase 11c — emitted by LeadScoringV2Service after each nightly scoring run.
    // Payload: {contactId, score, tier, source}.
    public static final String LEAD_SCORE_UPDATED = "leadScore.updated";

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

    // Phase 13b — Service Hub ticketing events. SlaBreachScheduler publishes
    // SLA_BREACHED; RuleEngine can trigger ESCALATE_TICKET action on it.
    public static final String TICKET_CREATED        = "ticket.created";
    public static final String TICKET_UPDATED        = "ticket.updated";
    public static final String TICKET_STATUS_CHANGED = "ticket.statusChanged";
    public static final String SLA_BREACHED          = "ticket.slaBreached";

    // Phase 13c — KB events. Phase 11's EmbeddingPipeline subscribes to these
    // to populate KnowledgeBaseArticle.embedding after articles are saved.
    public static final String KB_ARTICLE_CREATED = "kb.articleCreated";
    public static final String KB_ARTICLE_UPDATED = "kb.articleUpdated";

    // Phase C — Project / Milestone / Task delivery vertical. PROJECT_CREATED and
    // PROJECT_STATUS_CHANGED are emitted by ProjectService; MILESTONE_COMPLETED by
    // MilestoneService (payload includes triggersInvoice + nullable spawnedInvoiceId);
    // TASK_CREATED and TASK_STATUS_CHANGED by TaskService. All are advisory (RuleEngine
    // / webhook fan-out) — they do NOT drive Deal→Project (explicit endpoint) or the
    // Milestone→Invoice spawn (synchronous in MilestoneService.transition).
    public static final String PROJECT_CREATED       = "project.created";
    public static final String PROJECT_STATUS_CHANGED = "project.statusChanged";
    public static final String MILESTONE_COMPLETED   = "milestone.completed";
    public static final String TASK_CREATED          = "task.created";
    public static final String TASK_STATUS_CHANGED   = "task.statusChanged";

    // Phase D — Time & Expenses vertical. All are advisory (RuleEngine / webhook fan-out)
    // — they do NOT drive any core mutation (invoice creation, approval, billing status
    // updates are synchronous + explicit in TimeEntryService / ExpenseService).
    // TIME_ENTRY_LOGGED: emitted by TimeEntryService on manual create/update.
    // TIMER_STARTED / TIMER_STOPPED: emitted by startTimer / stopTimer.
    // TIME_INVOICED: emitted after invoice-from-time DRAFT created; payload includes
    //   {invoiceId, entryCount, totalSeconds}.
    // EXPENSE_SUBMITTED: emitted by ExpenseService.create.
    // EXPENSE_APPROVED / EXPENSE_REJECTED: emitted by approve/reject.
    // EXPENSE_INVOICED: emitted after invoice-from-expenses DRAFT created.
    public static final String TIME_ENTRY_LOGGED  = "timeEntry.logged";
    public static final String TIMER_STARTED      = "timer.started";
    public static final String TIMER_STOPPED      = "timer.stopped";
    public static final String TIME_INVOICED      = "time.invoiced";
    public static final String EXPENSE_SUBMITTED  = "expense.submitted";
    public static final String EXPENSE_APPROVED   = "expense.approved";
    public static final String EXPENSE_REJECTED   = "expense.rejected";
    public static final String EXPENSE_INVOICED   = "expense.invoiced";

    // Phase E — Recurring billing & Stripe money rails. All are advisory (RuleEngine /
    // webhook fan-out) — they do NOT drive the recurring spawn, the Stripe payment
    // recording, or the QBO push (those are synchronous + explicit / ledger-gated in
    // RecurringInvoiceSpawnService and StripeWebhookService). The Stripe webhook reuses
    // the existing INVOICE_PAID (above) — NOT a new event — so LoyaltyAccrualService
    // (which already keys off INVOICE_PAID) accrues on Stripe payments too.
    // RECURRING_INVOICE_CREATED: emitted by RecurringInvoiceService.create.
    // RECURRING_INVOICE_SPAWNED: emitted after a per-period DRAFT invoice is materialized;
    //   payload {recurringInvoiceId, spawnedInvoiceId, periodKey, occurrenceCount}.
    // RECURRING_INVOICE_PAUSED / RECURRING_INVOICE_ENDED: emitted on status transition
    //   to PAUSED / ENDED (ENDED also fires when the RRULE has no further occurrence or
    //   endAt is reached).
    // STRIPE_CHECKOUT_CREATED: emitted after a Stripe Checkout Session / Payment Link
    //   URL is generated; payload {invoiceId, mode}.
    public static final String RECURRING_INVOICE_CREATED = "recurringInvoice.created";
    public static final String RECURRING_INVOICE_SPAWNED = "recurringInvoice.spawned";
    public static final String RECURRING_INVOICE_PAUSED  = "recurringInvoice.paused";
    public static final String RECURRING_INVOICE_ENDED   = "recurringInvoice.ended";
    public static final String STRIPE_CHECKOUT_CREATED   = "stripe.checkoutCreated";

    // Phase F — Contracts & Documenso vertical. All are advisory (RuleEngine /
    // webhook fan-out) — they do NOT drive the SOW spawn, the signed-PDF store, or
    // the Deal→WON+Project promotion (those are synchronous + explicit / ledger-gated
    // in ContractService and DocumensoWebhookService). The Deal→WON promotion is NOT
    // event-driven — it is the synchronous reuse of DealCrudService.moveStage +
    // ProjectService.convertFromDeal inside the webhook handler's signed branch.
    // CONTRACT_CREATED: emitted by ContractService.create / spawnFromQuote.
    // CONTRACT_SENT: emitted after DocumensoClient.sendForSignature succeeds;
    //   payload {contractId, documensoDocumentId}.
    // CONTRACT_SIGNED: emitted after signedAt + signedPdfStorageRef are persisted;
    //   payload {contractId, dealId, kind, signedPdfStorageRef}.
    // CONTRACT_VOIDED: emitted by ContractService.setStatus(VOIDED).
    // CONTRACT_TEMPLATE_CREATED: emitted by ContractTemplateService.create.
    public static final String CONTRACT_CREATED          = "contract.created";
    public static final String CONTRACT_SENT             = "contract.sent";
    public static final String CONTRACT_SIGNED           = "contract.signed";
    public static final String CONTRACT_VOIDED           = "contract.voided";
    public static final String CONTRACT_TEMPLATE_CREATED = "contractTemplate.created";

    // Phase G — Portal expansion. All are advisory (RuleEngine / webhook fan-out) —
    // they do NOT drive any core mutation (invoice viewing, quote status transitions,
    // and activity creation are synchronous + explicit in the portal controllers and
    // the reused QuoteService / ActivityCrudService).
    // INVOICE_VIEWED_BY_CLIENT: emitted when a portal user views a specific invoice
    //   detail (GET /portal/me/invoices/{id}); payload {invoiceId, contactId, viewedByUserId}.
    //   An Activity(type=NOTE) row is also created via ActivityCrudService (best-effort)
    //   so the view appears on the contact timeline.
    // PORTAL_QUOTE_ACCEPTED: emitted after a portal user accepts a SENT quote;
    //   payload {quoteId, contactId}. The status transition delegates to the unchanged
    //   QuoteService.setStatus — no contract auto-spawn (Phase F's spawnFromQuote stays
    //   an explicit, separate, staff/explicit action).
    // PORTAL_QUOTE_DECLINED: emitted after a portal user declines a SENT quote;
    //   payload {quoteId, contactId}.
    public static final String INVOICE_VIEWED_BY_CLIENT = "invoice.viewedByClient";
    public static final String PORTAL_QUOTE_ACCEPTED    = "portal.quoteAccepted";
    public static final String PORTAL_QUOTE_DECLINED    = "portal.quoteDeclined";

    // Phase H — External-integrations glue. All are advisory (RuleEngine / webhook
    // fan-out) — they do NOT drive any core mutation; the Cal.com reconcile (H.2)
    // performs the Meeting projection upsert + Activity(MEETING) creation synchronously
    // and explicitly inside CalComWebhookService. Postmark-bounce routing (H.4) reuses
    // the existing EMAIL_BOUNCED / EMAIL_SPAM constants above (no new event).
    // The Activepieces subscription seed (H.5) and portal Zitadel federation (H.6)
    // add no new domain event.
    // CALCOM_BOOKING_SYNCED: emitted after a signature-verified Cal.com booking webhook
    //   reconciles a Meeting projection (created or rescheduled); payload
    //   {calComBookingUid, meetingId, contactId (nullable if unresolved)}.
    // CALCOM_BOOKING_CANCELLED: emitted after a verified Cal.com booking-cancelled
    //   webhook marks the Meeting projection cancelled; payload
    //   {calComBookingUid, meetingId}.
    public static final String CALCOM_BOOKING_SYNCED    = "calcom.bookingSynced";
    public static final String CALCOM_BOOKING_CANCELLED = "calcom.bookingCancelled";

    // Phase 1 (NMM AI intake) — voicemail-to-lead pipeline. All are advisory (RuleEngine /
    // webhook fan-out) — they do NOT drive any core mutation; the voicemail reconcile
    // (Feature A) performs the Contact find-or-create + Activity(CALL) creation +
    // notify-Rob/auto-ack-caller dispatch synchronously and explicitly inside
    // TwilioVoicemailService, gated by the ledger-insert-FIRST idempotency.
    // VOICEMAIL_RECEIVED: emitted after a signature-verified Twilio transcription callback
    //   is ledgered (one row per CallSid); payload {callSid, fromNumber, transcriptionStatus}.
    // VOICEMAIL_LEAD_CREATED: emitted after the Contact is found-or-created and the
    //   Activity(CALL, INBOUND) is logged; payload {callSid, contactId, activityId}.
    public static final String VOICEMAIL_RECEIVED     = "voicemail.received";
    public static final String VOICEMAIL_LEAD_CREATED = "voicemail.leadCreated";

    // Phase 2 (NMM AI intake) — "is this a mole?" photo-triage pipeline (Feature B). All are
    // advisory (RuleEngine / webhook fan-out) — they do NOT drive any core mutation; the photo
    // triage performs the Attachment store + MoleVisionService classify + Contact find-or-create
    // + Activity(NOTE) creation + notify-Rob dispatch synchronously and explicitly inside
    // MoleTriageService. There is no idempotency ledger (a public classify is intentionally
    // re-invocable — the ServiceRequestWidgetController precedent), so neither event is
    // dedupe-gated.
    // MOLE_PHOTO_CLASSIFIED: emitted after a stored homeowner photo is classified by the vision
    //   model; payload {classification, confidence, attachmentId, aboveThreshold}.
    // MOLE_LEAD_CREATED: emitted after the Contact is found-or-created and the Activity(NOTE) is
    //   logged; payload {classification, confidence, contactId, activityId, attachmentId}.
    public static final String MOLE_PHOTO_CLASSIFIED = "mole.photoClassified";
    public static final String MOLE_LEAD_CREATED     = "mole.leadCreated";

    private DomainEventType() {
    }
}
