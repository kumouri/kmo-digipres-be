# PHASE-PROGRESS — T1 "RE Database Goldmine" (`re-database-goldmine`)

> Fresh ledger for this branch (off `main` @ `00d4a1a`, post-E1–E4-engines-CLAUDE.md merge). Replaces the
> prior `get-paid-ar-collections` ledger that occupied this path — that work is already on `main`.
>
> **What:** Deploy the shipped E1 Nurture/Cadence engine to **real estate** — dormant-lead A/B/C/D
> auto-segmentation → tiered **fair-housing-safe** SMS nurture cadences with backoff → positive-reply
> auto-books a showing → per-segment ROI analytics. RE deployment + fair-housing guardrail + reply→book
> wiring + RE analytics surface + demo seed; NOT an engine reimplementation. Module
> `module/realestate/nurture/`; gates BOTH `kmosf.modules.realestate` AND `kmosf.modules.nurture`; error
> band **4360-4369**. Detail plan: `~/.claude/plans/re-database-goldmine.md`.

## Empty-diff-vs-extend decision
**Additively extend E1** `NurtureMessageComposer` with a strictly-additive `@Nullable NurtureCopyFilter`
SPI (the `conciergeRouter`/`intentRouter` setter precedent) — because the RE deployment runs through the
**same shared `NurtureRunner`**, whose compose-then-dispatch is a single method, so a pure RE-side
post-filter cannot intercept the copy before send. Null filter ⇒ byte-identical E1 ⇒ the 5 E1 nurture ITs
are the regression gate. The RE module wires a `FairHousingCopyFilter` (calls `FairHousingLint.lint`) onto
the composer at module init. The composer is the ONE E1 file with a (strictly additive) non-empty diff.

## Sub-phases

| Sub | Scope | Status |
|---|---|---|
| T1.0 | Detail plan + this fresh ledger | DONE |
| T1.1 | Additive E1 `NurtureCopyFilter` SPI + composer wiring; **re-ran 5 E1 nurture ITs (regression gate)** | DONE (`FairHousingCopyFilterTest` moves to T1.2 with the filter) |
| T1.2 | `module/realestate/nurture/`: `FairHousingCopyFilter`(+`Test`), `RealEstateNurtureReplyHandler` (E2 `IntentHandler`), `RealEstateNurtureService`+`RealEstateNurtureController`, `RealEstateNurtureAutoConfiguration` (two-module gate + composer-wiring side-effect bean), 4360-4369 Javadoc | DONE |
| T1.3 | Demo seeder (`@Profile("demo-realestate")` `RealEstateNurtureDemoSeeder`, "Gateway Realty" + 12 dormant leads + RE campaign) | DONE |
| T1.4 | T1 ITs (segmentation / fair-housing / reply-book / analytics) + ran new + regression ITs locally; counts captured | DONE |
| T1.5 | Regen+commit `docs/api/openapi.json`; update repo `CLAUDE.md` T1 entry; push + PR | TODO |

## Invariants (carried from E1)
- Fair-housing lint enforced on ALL RE nurture outbound (AI-personalized AND template), at the composer
  chokepoint → non-compliant copy falls back to a vetted safe template (never sent non-compliant, never
  dropped silently, logged). The headline correctness property; IT-proven.
- Module gate requires realestate AND nurture. Runner stays default-OFF
  (`kmosf.modules.nurture-runner.enabled` matchIfMissing=false).
- Reactive (no `.block()` on Netty loop). No live external (Twilio/Email `@MockitoBean`, Anthropic →
  WireMock). Per-tenant config never hardcoded. Reuse the E1 ledger-insert-FIRST + explicit-boolean
  invariants — never `switchIfEmpty(create/send)`.

## Regression gates (must stay green)
- `com.kumouri.kmodigipresbe.nurture.*` (5 ITs) — proves E1 byte-equivalent under the additive SPI.
- `RealEstateConciergeIT` — proves the RE module + inbound seam unaffected.
- `OpenApiEndpointIT` — full-context boot + spec regen.

## Log
- T1.0 — detail plan + ledger written. Reuse map verified against sources (E1 nurture engine, RE module
  incl. `FairHousingLint:72`, the E2 `IntentHandler`/`InboundIntentRouter` seam, `InboundSmsService:262`).
  Decided: additive E1 composer SPI (rationale above). Beginning T1.1.
- T1.1 — added `service/nurture/NurtureCopyFilter` SPI + the `@Nullable` field/setter/`applyFilter`
  wrapper in `NurtureMessageComposer` (existing logic verbatim → `composeRaw`; diff +63/-2, strictly
  additive). **Regression gate GREEN: all 5 E1 nurture ITs pass (21 tests, 0 failures)** —
  NurtureAnalyticsIT 1, NurtureCampaignControllerIT 6, NurtureReplyBookIT 5, NurtureRunnerIT 6,
  NurtureSegmentationIT 3. E1 byte-equivalent (null filter ⇒ identical output). `compileJava` clean.
- T1.2 — built `module/realestate/nurture/`: `FairHousingCopyFilter` (reuses `FairHousingLint`; flagged
  copy → per-channel vetted safe template; advisory code 4360); `RealEstateNurtureReplyHandler` (E2
  `IntentHandler`, vertical=realestate, positive intents → `NurtureReplyService.handlePositiveReplyByPhone`,
  4310 swallowed); `RealEstateNurtureService` + `controller/RealEstateNurtureController` (RE-scoped
  segment-and-enroll + analytics, ADMIN + both-module `requireEnabled`);
  `RealEstateNurtureAutoConfiguration` (gate = realestate `@ConditionalOnProperty` AND
  `@ConditionalOnBean(NurtureMessageComposer)`; wires the filter onto the composer via the
  `RealEstateNurtureSmsWiring` side-effect bean — the `ConciergeInboundSmsWiring` precedent). Registered
  in `AutoConfiguration.imports`. Error band 4360-4369 `<li>` added after E4's 4350-4359 in
  `GlobalErrorHandler`. `compileJava` clean; **`FairHousingCopyFilterTest` GREEN (7 tests, 0 failures)**.
- T1.3 — `RealEstateNurtureDemoSeeder` (`@Profile("demo-realestate")` `CommandLineRunner`, the
  `DataSeeder` precedent; idempotent on slug `gateway-realty`). Seeds tenant "Gateway Realty"
  (realestate+nurture+responder modules, $25 AI budget) + ADMIN user + Twilio connection
  (`config.bookingLink`/`notifyPhone`/`notifyEmail`, smsMode UNSET so the E2 reply handler path is live) +
  `ResponderConfig(vertical=realestate)` + the RE nurture campaign (A/B/C/D segments + SMS/email cadence) +
  12 dormant leads across the bands (3 A with WON deals ≥$300k, 4 B incl. 1 opted-out, 3 C incl. 1
  opted-out, 2 D), each with a backdated `Activity` so segmentation buckets deterministically. The 60-sec
  "watch this" documented in the class Javadoc + the detail plan. `compileJava` clean.
- T1.4 — wrote + ran the T1 ITs. **NEW T1 (all GREEN):** `RealEstateNurtureFairHousingIT` (4 — the
  headline: clean template verbatim; non-compliant template → safe fallback; non-compliant AI rewrite →
  caught by lint → safe fallback; opt-out → zero send), `RealEstateNurtureSegmentationIT` (2 — A/B/C/D
  bucketing incl. value-band A, opt-out + non-dormant skip; re-run idempotent),
  `RealEstateNurtureReplyBookIT` (3 — supports() matrix; positive reply → BOOKED + booking-link SMS +
  events; no-enrollment → ignored/zero-send), `RealEstateNurtureAnalyticsIT` (3 — per-segment funnel;
  non-ADMIN 1800; tenant-missing-nurture-module rejected), `FairHousingCopyFilterTest` (7, unit). Total
  19 T1 tests, 0 failures. **REGRESSION GREEN:** 5 E1 nurture ITs (21, 0 fail), `RealEstateConciergeIT`
  (5, 0 fail), `OpenApiEndpointIT` (2, 0 fail). Naming: `*IT` (full-ci lane) + `*Test` (fast lane).
