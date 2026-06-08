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

    // Phase J — Contractor / time-management vertical. All advisory (RuleEngine / webhook
    // fan-out) — they do NOT drive any core mutation (assignment, rate stamping, timesheet
    // approval, and invoicing are synchronous + explicit in the contractor services).
    // PROJECT_ASSIGNED: emitted by ProjectAssignmentService.assign on a fresh assignment;
    //   payload {projectId, userId}.
    // PROJECT_UNASSIGNED: emitted by ProjectAssignmentService.unassign (soft-delete);
    //   payload {projectId, userId}.
    public static final String PROJECT_ASSIGNED   = "project.assigned";
    public static final String PROJECT_UNASSIGNED = "project.unassigned";
    // J3 timesheet lifecycle — all advisory (TimesheetService is the single synchronous
    // writer of the period status AND the TimeEntry.approved invoicing gate; these do NOT
    // drive any core mutation).
    // TIMESHEET_SUBMITTED: emitted by TimesheetService.submit; payload {userId}.
    // TIMESHEET_APPROVED:  emitted by TimesheetService.approve (member entries flipped
    //   approved=true in the same op); payload {approvedBy}.
    // TIMESHEET_REJECTED:  emitted by TimesheetService.reject (member entries flipped back
    //   to approved=false); payload {rejectedBy, reason}.
    // TIMESHEET_REOPENED:  emitted by TimesheetService.reopen (REJECTED→OPEN; member entries
    //   flipped back to approved=false); payload {userId}.
    public static final String TIMESHEET_SUBMITTED = "timesheet.submitted";
    public static final String TIMESHEET_APPROVED  = "timesheet.approved";
    public static final String TIMESHEET_REJECTED  = "timesheet.rejected";
    public static final String TIMESHEET_REOPENED  = "timesheet.reopened";

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

    // HS-1 (Home Services — "Front Desk That Never Sleeps") — voicemail→DRAFT WorkOrder. Advisory
    // (RuleEngine / webhook fan-out) — does NOT drive the WorkOrder creation, which is synchronous
    // in TwilioVoicemailService after a multi-trade-tenant voicemail's strategy builds a DRAFT WO.
    // Emitted only for verticals that create a WorkOrder from a voicemail (home-services), never for
    // mole (whose strategy creates none). Payload: {callSid, workOrderId, contactId, trade, urgency}.
    public static final String VOICEMAIL_WORK_ORDER_DRAFTED = "voicemail.workOrderDrafted";

    // HS-2 (Home Services — "Front Desk That Never Sleeps") — equipment-nameplate photo enrichment.
    // Advisory (RuleEngine / webhook fan-out) — does NOT drive any mutation; the WorkOrder enrichment
    // + Activity(NOTE) + owner-digest notify happen synchronously and explicitly inside
    // EquipmentVisionService after a caller uploads an equipment photo via the tokenized link the
    // HS-1 auto-ack carried. Emitted on EVERY read (even a blank/no-legible-nameplate one — enriched
    // is false then). There is no idempotency ledger (a per-caller photo upload is intentionally
    // re-invocable — the MoleTriageController/MoleTripwireController precedent). Payload:
    // {workOrderId, attachmentId, enriched, make?, model?, serial?, equipmentType?, observedSymptom?}.
    public static final String EQUIPMENT_PHOTO_READ = "equipment.photoRead";

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

    // Phase 3 (NMM AI intake) — coverage-window automation. All are advisory (RuleEngine /
    // webhook fan-out) — they do NOT drive any core mutation; the B2 re-activity tripwire
    // performs the Attachment store + MoleVisionService classify + re-treatment Milestone
    // creation (via the UNCHANGED MilestoneService.create) + notify-Rob dispatch synchronously
    // and explicitly inside MoleTripwireService, and the default-OFF coverage-window nudge job
    // dispatches the check-in SMS synchronously (idempotent per (project, period) via an
    // explicit-boolean CoverageNudgeLog ledger probe). Like Phase 2, the tripwire has no
    // idempotency ledger (a per-customer photo report is intentionally re-invocable), so the
    // tripwire events are not dedupe-gated.
    // MOLE_TRIPWIRE_REPORTED: emitted after a coverage customer's tripwire photo is classified;
    //   payload {classification, confidence, attachmentId, projectId, aboveThreshold}.
    // RETREATMENT_MILESTONE_CREATED: emitted after an above-threshold mole auto-creates a
    //   re-treatment Milestone on the customer's Project; payload {projectId, milestoneId,
    //   classification, confidence, attachmentId}.
    // COVERAGE_NUDGE_SENT: emitted after the default-OFF nudge job dispatches a check-in SMS for
    //   a (project, period); payload {projectId, periodKey, contactId}.
    public static final String MOLE_TRIPWIRE_REPORTED       = "mole.tripwireReported";
    public static final String RETREATMENT_MILESTONE_CREATED = "mole.retreatmentMilestoneCreated";
    public static final String COVERAGE_NUDGE_SENT          = "coverage.nudgeSent";

    // NMM GBP review-reply automation — Google Business Profile review-reply pipeline. Both are
    // advisory (RuleEngine / webhook fan-out) — they do NOT drive any core mutation; the
    // default-OFF GbpReviewPoller performs the GbpReviewReply ledger-insert-FIRST + GbpReplyDraftService
    // draft + notify-Rob dispatch synchronously and explicitly inside the poll cycle, gated by the
    // explicit-boolean review-id idempotency probe, and the optional auto-post / the admin
    // approve-and-post endpoint perform the GbpApiClient.postReply synchronously.
    // GBP_REVIEW_REPLY_DRAFTED: emitted after a NEW review is ledgered and an on-brand reply is
    //   drafted (status DRAFTED); payload {reviewId, rating, reviewReplyId, autoPost}.
    // GBP_REVIEW_REPLY_POSTED: emitted after the (optionally edited) reply is posted back to Google
    //   (status POSTED) — by the optional poller auto-post OR the admin approve-and-post endpoint;
    //   payload {reviewId, reviewReplyId}.
    public static final String GBP_REVIEW_REPLY_DRAFTED = "gbp.reviewReplyDrafted";
    public static final String GBP_REVIEW_REPLY_POSTED  = "gbp.reviewReplyPosted";

    // ChairFill CF-1 — nightly no-show risk scoring. Emitted by the chairfill-module-gated
    // NoShowRiskScoringService once per UPCOMING salon Booking that gets a NoShowRisk stamped
    // (terminal bookings are never re-stamped, so never emit). Advisory (RuleEngine / webhook
    // fan-out) — does NOT drive any core mutation; the CF-2 RiskTieredPreventionService
    // subscribes to branch on tier (HIGH -> deposit-require + extra-confirm; LOW/MEDIUM ->
    // personalized reminder). Payload:
    // {bookingId, contactId, staffMemberId, riskTier, riskScore, source}.
    public static final String BOOKING_RISK_SCORED = "booking.riskScored";

    // ChairFill CF-3 — gap-fill waitlist auto-offer. All three are advisory (RuleEngine / webhook
    // fan-out) — they do NOT drive any core mutation; the gap-fill (rank → Claude offer → atomic
    // first-YES claim → booking) happens synchronously and explicitly inside the chairfill GapFillService
    // / WaitlistClaimService, gated by the slot-level findAndModify claim + the WaitlistOffer @Version.
    // BOOKING_CANCELLED: emitted by SalonBookingService.cancel() after a salon Booking transitions to
    //   CANCELLED (the additive emit — the method emitted nothing before). The reliable gap-fill trigger.
    //   Payload: {bookingId, contactId, staffMemberId, serviceMenuItemId, scheduledStart, scheduledEnd}.
    //   Distinct from the H.2 CALCOM_BOOKING_CANCELLED (a decoupled Meeting projection with no salon
    //   bookingId). Non-chairfill tenants have no subscriber, so this is a harmless no-op there.
    // WAITLIST_OFFER_SENT: emitted by GapFillService once per WaitlistOffer dispatched (a ranked
    //   waitlisted contact texted a time-boxed offer for the freed slot). Payload:
    //   {bookingId, offerId, contactId, rank, expiresAt}.
    // WAITLIST_SLOT_CLAIMED: emitted by WaitlistClaimService when the first YES atomically claims the
    //   freed slot and a real Booking is created for the winner. Payload:
    //   {freedBookingId, newBookingId, offerId, contactId}.
    public static final String BOOKING_CANCELLED      = "booking.cancelled";
    public static final String WAITLIST_OFFER_SENT    = "waitlist.offerSent";
    public static final String WAITLIST_SLOT_CLAIMED  = "waitlist.slotClaimed";

    // Real Estate Concierge RE-1 — the grounded listing concierge over inbound SMS. Both are advisory
    // (RuleEngine / webhook fan-out) — they do NOT drive any core mutation; the disclosure-text indexing
    // and the inbound answer/handoff happen synchronously and explicitly inside the realestate-module-gated
    // ListingDisclosureService / ConciergeInboundRouter. Emitted only when the realestate module is on.
    // LISTING_DISCLOSURE_INDEXED: emitted by ListingDisclosureService after a disclosure's text is embedded
    //   and upserted into the vector index as source type "ListingDisclosure" with listingId metadata
    //   (RE-1 §3 / §6.3). Payload: {listingId, disclosureId, disclosureType}.
    // CONCIERGE_INBOUND_RECEIVED: emitted by ConciergeInboundRouter after a buyer inbound SMS is correlated
    //   to a listing + conversation and the buyer turn is appended (RE-1 §6.6). Payload:
    //   {listingId, conversationId, buyerPhone}.
    public static final String LISTING_DISCLOSURE_INDEXED = "realestate.listingDisclosureIndexed";
    public static final String CONCIERGE_INBOUND_RECEIVED = "realestate.conciergeInboundReceived";

    // Real Estate Concierge RE-2 — multi-turn qualification + hot-handoff. Both advisory (RuleEngine /
    // webhook fan-out) — they do NOT drive any core mutation; the qualification materializes the buyer
    // Contact + Deal synchronously and explicitly inside the realestate-module-gated QualificationService,
    // and the hot-handoff notifies the agent synchronously inside LeadHandoffService. RE-2 adds NO new ML
    // and does NOT modify LeadScoringV2Service — the materialized Deal flows through the UNCHANGED nightly
    // scorer, which emits the existing LEAD_SCORE_UPDATED (above); LeadHandoffService subscribes to THAT.
    // CONCIERGE_LEAD_QUALIFIED: emitted by QualificationService after enough signal (a budget) materializes
    //   / updates the buyer's concierge-sourced Deal. Payload: {conversationId, contactId, dealId, listingId}.
    // CONCIERGE_HOT_HANDOFF: emitted by LeadHandoffService after a HOT LEAD_SCORE_UPDATED for a contact with
    //   a concierge-sourced realestate Deal triggers the best-effort agent alert. Payload:
    //   {contactId, dealId, listingId, score, tier}.
    public static final String CONCIERGE_LEAD_QUALIFIED = "realestate.conciergeLeadQualified";
    public static final String CONCIERGE_HOT_HANDOFF     = "realestate.conciergeHotHandoff";

    private DomainEventType() {
    }
}
