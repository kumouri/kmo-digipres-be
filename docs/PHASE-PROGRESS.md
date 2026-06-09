# PHASE-PROGRESS — E3 Review Engine (`review-engine-requests`)

> Fresh ledger for this branch (off `main` @ `3fe15ab`, post-E2-responder merge). Replaces the prior
> E2-responder ledger that occupied this path — that work is already on `main`.
>
> Detail plan: `~/.claude/plans/review-engine-requests.md`. Error band **4340–4349**.
> Module gate `kmosf.modules.gbp-reviews` (admin/insights controller, matchIfMissing=true); the new
> `ReviewRequestSenderJob` is **default-OFF** (`kmosf.modules.review-engine.sender-enabled`,
> matchIfMissing=**false**, no line in `application.properties`).
>
> **Acceptance bar = the regression:** the 5 in-use GBP ITs MUST stay green —
> `GbpReplyDraftServiceIT`, `GbpReviewPollerIT`, `GbpReviewReplyAdminIT`, `GbpTokenRefreshIT`,
> `GbpTokenServiceTest`. The ONLY edit to a shipped *service* is the surgical additive sentiment-store +
> negative-alert seam in `GbpReviewPoller.draftAndFinish` (all `onErrorResume`'d; reply-draft behavior
> unchanged). `GbpReplyDraftService`/`GbpReviewReplyAdminService`/`GbpApiClient`/`TwilioSmsService`/
> `EmailService`/`AnthropicAiAssistService`/`SalonBookingService`/`MilestoneService` stay empty-diff.

## Completion events subscribed (verified)

- `BOOKING_COMPLETED` (`SalonBookingService.complete`) — payload `{bookingId, contactId, staffMemberId, …}`
  → attribution `subjectType=STAFF, subjectId=staffMemberId`, contactId from payload.
- `MILESTONE_COMPLETED` (`MilestoneService`) — payload `{projectId, milestoneId, …}`
  → attribution `subjectType=PROJECT, subjectId=projectId`, contactId resolved from `Project.primaryContactId`.
- (No `WORK_ORDER_COMPLETED` / `APPOINTMENT_COMPLETED` event exists — frontdesk out of E3 scope; D3.)

## Sub-phase ledger

| Sub-phase | Scope | Commit | Status |
|---|---|---|---|
| E3.0 | Detail plan + fresh ledger | 22c2245 | DONE |
| E3.1 | `ReviewRequest` model + `ReviewSubjectType`/`ReviewSentiment`/`SentimentSource` enums + `GbpReviewReply` +2 nullable fields + `ReviewRequestRepository` + `ReviewInsightsService` + `ReviewInsights` projection + `ReviewInsightsController` + `4340` + GlobalErrorHandler `<li>` + `DomainEventType` E3 block | 81cd619 | DONE |
| E3.2 | `ReviewRequestService` (subscriber, create-on-completion idempotent) + `ReviewRequestSenderJob` (default-OFF, due-send, atomic-claim idempotent, opt-out, freq-cap, no-incentive template) + `kmosf.review-engine.*` config | a3c3ba9 | DONE |
| E3.3 | `ReviewSentimentService` + `ReviewNegativeAlertService` + the one `GbpReviewPoller` seam (AI-refine + alert both default-OFF → existing poller byte-identical) | 7d33135 | DONE |
| E3.4 | openapi regen (`docs/api/openapi.json`) + ledger finalize | _this commit_ | DONE |

## Test ledger (filled as ITs land)

| IT/Test | Covers | Result |
|---|---|---|
| `ReviewInsightsIT` | per-subject + per-tenant aggregation; cross-tenant isolation; case-insensitive type; 4340; 1800 | **5/0** |
| `ReviewRequestCreationIT` | BOOKING_COMPLETED + MILESTONE_COMPLETED → one PENDING each, attribution; no-staff→OTHER; re-emit idempotent; no-contact → none ×2 | **6/0** |
| `ReviewRequestSenderIT` | default-OFF; opted-ON sends due once + atomic claim; zero-dup 2nd sweep; opt-out skip; no-reviewLink stays PENDING; freq-cap; no-incentive template | **4/0** |
| `ReviewSentimentServiceIT` | rating-only (no AI call) 5/3/1; commented→AI refine source AI; upstream 500→degrade to RATING | **3/0** |
| `GbpReviewSentimentAlertIT` | poller path: 2★→NEGATIVE stored + manager alert email+SMS + event; 5★→POSITIVE no alert; existing poller behavior preserved (2 DRAFTED + 2 events) | **1/0** |
| REGRESSION `GbpReplyDraftServiceIT` | unchanged GBP draft | **4/0** |
| REGRESSION `GbpReviewPollerIT` | unchanged GBP poll (the headline regression gate) | **2/0** |
| REGRESSION `GbpReviewReplyAdminIT` | unchanged GBP admin approve/post | **6/0** |
| REGRESSION `GbpTokenRefreshIT` | unchanged GBP OAuth refresh | **3/0** |
| REGRESSION `GbpTokenServiceTest` | unchanged GBP token service (no-Docker) | **5/0** |
| `OpenApiEndpointIT` | context boots + 2 new `/gbp/review-insights` paths in spec | **2/0** |

## Key invariants for this branch (carry-forward)

- **`switchIfEmpty` only for genuine not-found.** Request creation is explicit-boolean over the unique
  `tenant_subject_contact_idx`; request send is atomic-claim/ledger-insert-FIRST; sentiment/alert ride the
  poller's existing review-id idempotency. **Never `switchIfEmpty(create/send/process)`.**
- **No-incentive template** — the review-request SMS carries no discount/gift/reward language (Google 2026
  policy); asserted in `ReviewRequestSenderIT`.
- **Default-OFF sender** — `ReviewRequestSenderJob` bean not created unless opted-in; no live request SMS in CI.
- **No live external** — Anthropic (sentiment) → WireMock; Twilio/Email send → `@MockitoBean`; sandbox fakes;
  per-tenant review link (never hardcoded).
- **Empty-diff** (verify `git diff main`, 0 lines each): `GbpReplyDraftService`, `GbpReviewReplyAdminService`,
  `GbpReviewReplyAdminController`, `GbpApiClient`, `GbpTokenService`, `GbpProperties`, `GbpConfig`,
  `TwilioSmsService`, `EmailService`, `AnthropicAiAssistService`, `AiUsageRecorder`, `SalonBookingService`,
  `MilestoneService`, `IntegrationConnection`(+repo+service). The pre-existing files touched: `GbpReviewPoller`
  (surgical sentiment+alert seam — the ONLY shipped *service*), `GbpReviewReply` (+2 nullable fields),
  `GbpReviewReplyRepository` (+1 additive `findByTenantId` finder), `GlobalErrorHandler` (Javadoc `<li>`),
  `DomainEventType` (additive E3 block), `application.properties` (additive keys), `docs/api/openapi.json` (regen).

## Deviations / decisions (running)

- **D1** — admin/insights controller uses `@ConditionalOnProperty(kmosf.modules.gbp-reviews, matchIfMissing=true)`
  + `RoleGuard.requireRole("ADMIN")` (the shipped `GbpReviewReplyAdminController` precedent), **NOT**
  `requireEnabled("gbp-reviews")` — `gbp-reviews` is not a registered `ModuleDefinition` so `requireEnabled`
  would error 1130. The briefing's `requireEnabled` is a carry-over from the E1/E2 `module/`-based engines.
- **D2** — `GbpReviewReply` +2 additive nullable fields (`sentiment`, `sentimentSource`); additive-nullable
  precedent (E-D8 `paymentTerms`); no index/contract change; legacy rows null.
- **D3** — subscribe to the 2 completion events that exist (salon booking + project milestone); frontdesk
  Appointment has no completion event (out of scope; generic subscriber picks one up later for free).
- **D4** — send-idempotency seam: **atomic PENDING→SENT claim** chosen (a conditional
  `ReactiveMongoTemplate.findAndModify` on the `ReviewRequest` itself: `{_id, tenantId, status:PENDING}` →
  `{status:SENT, sentAt}`, `returnNew`; winner-only send, `null` = already claimed → zero duplicate). The
  `WaitlistClaimService` per-slot `findAndModify` precedent — exactly-once with one fewer collection than a
  separate `ReviewRequestSendLog`. `markSkipped` uses the same atomic PENDING→SKIPPED flip.
- **D5 (regression-gate resolution — important)** — the surgical `GbpReviewPoller` sentiment+alert seam
  initially regressed `GbpReviewPollerIT` (it pins exactly 2 Anthropic POSTs + 2 notify SMS; the seam's AI
  refine added Anthropic calls and the negative alert added an SMS for the seeded 2★ review). Resolved by
  making **both the AI refinement (`kmosf.review-engine.ai-refine-enabled`) and the negative alert
  (`kmosf.review-engine.negative-alert-enabled`) DEFAULT-OFF**: by default the seam stores only the
  rating-based sentiment (a Mongo field write, no external call) and fires no alert, so the existing poller
  behavior is byte-identical and `GbpReviewPollerIT` passes unchanged (2/0). The new ITs opt the flags ON to
  exercise the AI-refine + alert paths. Rationale: the poller already notifies on every new review, so the
  dedicated negative alert is a legitimate opt-in extra (not a default), and the AI refine is a cost opt-in.
  No existing gbp IT was modified.
