# PHASE-PROGRESS — T5 Home Services "Instant Callback" (`home-instant-callback`)

> Fresh ledger for this branch (off `main` @ `eb30acb`). Replaces the prior `health-switchboard-ai`
> (T4) ledger that occupied this path — that work is already on `main`. Tracks per-sub-phase progress
> + validation status so a resume-after-crash reconstructs the frontier from git + this file, never
> agent memory.

Detail plan: `~/.claude/plans/home-instant-callback.md`. Error band **4400–4409**. Module gate:
`kmosf.modules.home-services` AND `kmosf.modules.responder` (the callback-offer send default-OFF via
`kmosf.modules.home-callback-offer`, matchIfMissing=false). Consumes **E2** (Inbound Responder +
Intent-Router) — the T3/T4 structural twin for Home Services. Wave-2 vertical AI tool over E1–E4.

## What T5 is
Missed-call voicemail → an opt-in callback SMS to the caller → the caller's reply (handled via the E2
responder as a `CallbackIntentHandler`) records a revenue-ranked `CallbackRequest` → a dispatcher read
endpoint returns ranked callback cards with the AI one-liner → recovery (missed→offered→accepted→
dispatched) analytics. Heavy reuse of the shipped HS-1 voicemail intake + E2 responder; the reused
voicemail core (`TwilioVoicemailService`) stays **byte-unchanged** via a `VOICEMAIL_LEAD_CREATED`
domain-event subscriber (no seam).

## Sub-phases

| # | Sub-phase | Status | Commit | Validation |
|---|-----------|--------|--------|------------|
| T5.1 | Detail plan + this ledger | DONE | (first commit) | n/a (docs) |
| T5.2 | Model + ledgers (`CallbackRequest`, `CallbackOfferLog`, `CallbackFunnelLog`, enums, repos) | PENDING | | |
| T5.3 | `CallbackIntentHandler` (E2 handler) + `CallbackOfferSubscriber` (`VOICEMAIL_LEAD_CREATED`) + `CallbackIntents` + parsers | PENDING | | |
| T5.4 | `CallbackRevenueRanker` + `CallbackQueueService` + `CallbackController` (ranked queue + dispatch) | PENDING | | |
| T5.5 | `CallbackAnalyticsService` (recovery funnel) + `CallbackConfig` + admin CRUD | PENDING | | |
| T5.6 | `CallbackAutoConfiguration` (both-modules gate; default-OFF send bean) | PENDING | | |
| T5.7 | Demo seed (`CallbackDemoSeeder`, `@Profile("demo-home-callback")`) | PENDING | | |
| T5.8 | ITs (offer / reply / revenue-rank / recovery-stats / module-gate / opt-out) + regression | PENDING | | |
| T5.9 | Docs (CLAUDE.md T5 entry) + error codes (4400-4409 Javadoc) + `DomainEventType` T5 block + openapi regen | PENDING | | |

## Decisions / deviations (filled in as work lands)
- **Reused voicemail core empty-diff via event subscriber (T5.3).** The callback-offer SMS hooks off
  the shipped `DomainEventType.VOICEMAIL_LEAD_CREATED` (already published by `TwilioVoicemailService`
  after the Contact+Activity are durable), so `TwilioVoicemailService` needs **no seam** — strictly
  additive subscriber. Strongest possible "reused core empty-diff." Scoped to home tenants
  (`ResponderConfig(vertical="home")`) so a mole/NMM lead is a no-op (NMM byte-equivalent).
- **Default-OFF caller-SMS send.** Only the offer-send bean carries the extra
  `@ConditionalOnProperty(kmosf.modules.home-callback-offer, matchIfMissing=false)` gate — no live
  caller SMS in CI / any default run (the AR/Nurture comms-runner precedent). The handler + read
  endpoints + analytics are on with the both-modules gate.
- **No E2 edit.** `CallbackIntentHandler` registers purely by being a `@Bean`; the
  `InboundIntentRouter` auto-discovers it via `List<IntentHandler>`. The reply send + consent gate +
  cap + `ConversationState` persistence + advisory events are all the router's (the handler returns a
  `HandlerResult` only).
- **Deterministic revenue ranking, no ML.** `CallbackRevenueRanker` is pure/static — job-value band
  (LARGE>MEDIUM>SMALL>unknown) primary, urgency secondary, recency tie-break — reusing the WorkOrder
  `customFields.jobValueBand`/`urgency` the multi-trade intake already stamped.

## Validation log
- T5.1: docs only — no build needed.
