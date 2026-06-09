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
| T6.1 | Detail plan + this ledger | DONE | (first commit) | n/a (docs) |
| T6.2 | `module/chairfill/reviewboost/` — DTOs + `SalonReviewInsightsService` (reuses `ReviewInsightsService` + `StaffMemberRepository`) + `ReviewBoostController` + `ReviewBoostAutoConfiguration`; register in AutoConfiguration.imports; app-props doc; `GlobalErrorHandler` 4410-4419 Javadoc | PENDING | — |
| T6.3 | `SalonReviewBoostDemoSeeder` (`@Profile("demo-salon-reviewboost")`) | PENDING | — |
| T6.4 | ITs: `SalonReviewBoostInsightsIT`, `SalonReviewBoostConfigIT` | PENDING | — |
| T6.5 | openapi regen + CLAUDE.md T6 entry + ledger finalize | PENDING | — |

## Reused cores — MUST stay empty-diff vs `main`
`ReviewRequestService`, `ReviewSentimentService`, `ReviewNegativeAlertService`, `ReviewInsightsService`,
`ReviewRequestSenderJob`, `GbpReplyDraftService`, `GbpReviewReplyAdminController`/`Service`,
`SalonReviewReplyService`, `TwilioSmsService`, `SalonBookingService`, `Booking`, `StaffMember`,
`ReviewInsightsController`, `ReviewInsights`, `ReviewRequest`(+repo). No seam needed.

## Validation log
(filled as sub-phases complete)
