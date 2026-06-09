# PHASE-PROGRESS — T6 Salon "ReviewBoost" (BE leg) (`salon-reviewboost`)

> Fresh ledger for this branch (off `main` @ `b345d27`). Replaces the prior `home-instant-callback`
> (T5) ledger that occupied this path — that work is already on `main`. Tracks per-sub-phase progress
> + validation status so a resume-after-crash reconstructs the frontier from git + this file, never
> agent memory.

Detail plan: `~/.claude/plans/salon-reviewboost.md`. Error band **4410–4419** (reuse E3's 4340-4349;
mint only the genuinely-new). Deploys engine **E3** (Review engine: requests + insights, PR #104) to the
salon vertical. Module gate: `kmosf.modules.chairfill` (the salon flagship's key) + `@ConditionalOnBean
(SalonBookingService)` (salon-spa loaded) + per-tenant `requireEnabled(chairfill)` & `requireEnabled
(salon-spa)`. Wave-2 vertical AI tool over E1–E4.

## Framing (investigation result — what already ships)
Most of ReviewBoost ALREADY SHIPS via E3 + ChairFill (CF-1..CF-5). T6's genuine net-new is a
**per-stylist insights LIST** read surface + a demo seed.

- **Per-stylist attribution already works** — `SalonBookingService.complete()` emits `BOOKING_COMPLETED`
  carrying `staffMemberId`; `ReviewRequestService.handleBookingCompleted` maps it to
  `ReviewSubjectType.STAFF` + `staffMemberId`. T6 asserts it, does not change it.
- **No-incentive review-request SMS** — `ReviewRequestSenderJob` (default-OFF; per-tenant `reviewLink`).
- **Sentiment triage + negative manager alert** — `ReviewSentimentService` + `ReviewNegativeAlertService`.
- **1-click AI reply drafts + approval queue** — `GbpReplyDraftService` + CF-4 `SalonReviewReplyService`
  + `GbpReviewReplyAdminController`.
- **Per-(subjectType,subjectId) insights** — `ReviewInsightsService` + `ReviewInsightsController`.

The shipped insights API answers one subject at a time (or whole-tenant). The salon dashboard needs every
stylist's funnel side-by-side — that LIST is T6's only real net-new BE code.

## Sub-phases

| # | Sub-phase | Status | Commit | Validation |
|---|-----------|--------|--------|------------|
| T6.1 | Detail plan + this ledger | DONE | ebc295e | n/a (docs) |
| T6.2 | `module/chairfill/reviewboost/` — DTOs + `SalonReviewInsightsService` (reuses `ReviewInsightsService` + `StaffMemberRepository`) + `ReviewBoostController` + `ReviewBoostAutoConfiguration`; register in AutoConfiguration.imports; app-props doc; `GlobalErrorHandler` 4410-4419 Javadoc | DONE | (this) | compileJava OK |
| T6.3 | `SalonReviewBoostDemoSeeder` (`@Profile("demo-salon-reviewboost")`) | DONE | (this) | compileJava OK |
| T6.4 | ITs: `SalonReviewBoostInsightsIT` (5), `SalonReviewBoostConfigIT` (4) | DONE | (this) | 9/9 GREEN (Docker); end-to-end real complete()->engine attribution proven |
| T6.5 | openapi regen + CLAUDE.md T6 entry + ledger finalize | DONE | (this) | openapi no-diff (default-OFF; committed cp1252 spec unchanged, verifyOpenApi green) |

## Validation log
- T6.4: `SalonReviewBoostInsightsIT` 5/5 + `SalonReviewBoostConfigIT` 4/4 GREEN (Testcontainers, Docker
  29.4.3). The headline `completingABooking_createsStylistAttributedReviewRequest_viaTheShippedEngine`
  drives the REAL `SalonBookingService.complete()` → the unchanged `ReviewRequestService` subscriber →
  asserts a `ReviewRequest(STAFF, staffMemberId)` — proving per-stylist attribution works end-to-end with
  zero T6 change to the create path.
- T6.5 regression (all GREEN, 91 tests / 0 failures / 0 errors): `integration.gbp.*` (E3 —
  GbpReplyDraftServiceIT 4, GbpReviewPollerIT 2, GbpReviewReplyAdminIT 6, GbpReviewSentimentAlertIT 1,
  GbpTokenRefreshIT 3, GbpTokenServiceTest 5, ReviewInsightsIT 5, ReviewRequestCreationIT 6,
  ReviewRequestSenderIT 4, ReviewSentimentServiceIT 3), `module.chairfill.*` (CF — GapFillWaitlistIT 11,
  NoShowRiskScoringIT 8, OfferExpirySweepIT 3, RiskTieredPreventionIT 5, SalonReviewReplyIT 8,
  WaitlistBoardIT 6, + the 2 T6 ITs), `OpenApiEndpointIT` 2.
- Empty-diff verified (`git diff main -- <file>` = 0 lines each): ReviewRequestService,
  ReviewSentimentService, ReviewNegativeAlertService, ReviewInsightsService, ReviewRequestSenderJob,
  GbpReplyDraftService, ReviewInsightsController, ReviewInsights, ReviewRequest, SalonBookingService,
  Booking, StaffMember, SalonReviewReplyService, ChairFillAutoConfiguration.
- openapi: ReviewBoost endpoints are default-OFF → absent from the spec (the flagship-5b/AR/T1-T5
  precedent). The committed cp1252 `docs/api/openapi.json` is unchanged (the only test-gen delta was a
  cp1252→UTF-8 em-dash re-encoding, reverted); `verifyOpenApi` green.

## T6 net-new endpoints (FE leg types to these)
- `GET /api/v1/chairfill/reviewboost/insights` → `Mono<SalonReviewBoardDTO>` (ADMIN)
  - `SalonReviewBoardDTO(long reviewCount, double averageRating, long positiveCount, long neutralCount,
    long negativeCount, long unclassifiedCount, long totalRequestsSent, long totalRequestsResponded,
    double overallResponseRate, List<StylistReviewStatsDTO> stylists)`
  - `StylistReviewStatsDTO(UUID staffMemberId, String displayName, long requestsSent,
    long requestsResponded, double responseRate)`
- `GET /api/v1/chairfill/reviewboost/config` → `Mono<ReviewBoostConfigDTO>` (ADMIN)
  - `ReviewBoostConfigDTO(boolean reviewLinkConfigured, String reviewLink, boolean senderEnabled,
    boolean sentimentRefineEnabled, boolean negativeAlertEnabled)`

## Reused cores — MUST stay empty-diff vs `main`
`ReviewRequestService`, `ReviewSentimentService`, `ReviewNegativeAlertService`, `ReviewInsightsService`,
`ReviewRequestSenderJob`, `GbpReplyDraftService`, `GbpReviewReplyAdminController`/`Service`,
`SalonReviewReplyService`, `TwilioSmsService`, `SalonBookingService`, `Booking`, `StaffMember`,
`ReviewInsightsController`, `ReviewInsights`, `ReviewRequest`(+repo). No seam needed.
