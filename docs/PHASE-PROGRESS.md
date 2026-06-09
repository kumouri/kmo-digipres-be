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
| E3.0 | Detail plan + fresh ledger | _this commit_ | IN PROGRESS |
| E3.1 | `ReviewRequest` model + `ReviewSubjectType`/`ReviewSentiment`/`SentimentSource` enums + `GbpReviewReply` +2 nullable fields + `ReviewRequestRepository` + `ReviewInsightsService` + `ReviewInsights` projection + `ReviewInsightsController` + `4340` + GlobalErrorHandler `<li>` + `DomainEventType` E3 block | — | TODO |
| E3.2 | `ReviewRequestService` (subscriber, create-on-completion idempotent) + `ReviewRequestSenderJob` (default-OFF, due-send, claim/ledger idempotent, opt-out, freq-cap, no-incentive template) + `kmosf.review-engine.*` config | — | TODO |
| E3.3 | `ReviewSentimentService` + `ReviewNegativeAlertService` + the one `GbpReviewPoller` seam | — | TODO |
| E3.4 | openapi regen (`docs/api/openapi.json`) + ledger finalize | — | TODO |

## Test ledger (filled as ITs land)

| IT/Test | Covers | Result |
|---|---|---|
| `ReviewInsightsIT` | per-subject + per-tenant aggregation; cross-tenant isolation | — |
| `ReviewRequestCreationIT` | BOOKING_COMPLETED + MILESTONE_COMPLETED → one PENDING each, attribution; re-emit idempotent; no-contact → none | — |
| `ReviewRequestSenderIT` | default-OFF; opted-ON sends due once + claims; zero-dup 2nd sweep; opt-out skip; no-reviewLink skip; freq-cap; no-incentive template | — |
| `ReviewSentimentServiceIT` | rating-based 5/3/1; WireMock-Anthropic refine; degrade on 500 | — |
| `GbpReviewSentimentAlertIT` | poller path: negative → sentiment stored + manager alert; positive → no alert; existing poller behavior preserved | — |
| REGRESSION `GbpReplyDraftServiceIT` | unchanged GBP draft | — |
| REGRESSION `GbpReviewPollerIT` | unchanged GBP poll (the headline regression gate) | — |
| REGRESSION `GbpReviewReplyAdminIT` | unchanged GBP admin approve/post | — |
| REGRESSION `GbpTokenRefreshIT` | unchanged GBP OAuth refresh | — |
| REGRESSION `GbpTokenServiceTest` | unchanged GBP token service (no-Docker) | — |
| `OpenApiEndpointIT` | context boots + 2 new `/gbp/review-insights` paths in spec | — |

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
  `GbpApiClient`, `GbpTokenService`, `GbpReviewReplyRepository`, `TwilioSmsService`, `EmailService`,
  `AnthropicAiAssistService`, `AiUsageRecorder`, `SalonBookingService`, `MilestoneService`,
  `IntegrationConnection`(+repo+service). The only pre-existing files touched: `GbpReviewPoller` (surgical
  sentiment+alert seam), `GbpReviewReply` (+2 nullable fields), `GlobalErrorHandler` (Javadoc `<li>`),
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
- **D4** — send-idempotency seam: atomic PENDING→SENT claim vs a `ReviewRequestSendLog` ledger — decided in E3.2
  (record which here once implemented).
