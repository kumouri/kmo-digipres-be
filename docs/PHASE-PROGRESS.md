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
| T1.0 | Detail plan + this fresh ledger | DONE (this commit) |
| T1.1 | Additive E1 `NurtureCopyFilter` SPI + composer wiring + `FairHousingCopyFilterTest`; **re-run 5 E1 nurture ITs (regression gate)** | TODO |
| T1.2 | `module/realestate/nurture/`: `FairHousingCopyFilter`, `RealEstateNurtureReplyHandler` (E2 `IntentHandler`), `RealEstateNurtureService`+`RealEstateNurtureController`, `RealEstateNurtureAutoConfiguration` (two-module gate + composer-wiring side-effect bean), config props, 4360-4369 Javadoc | TODO |
| T1.3 | Demo seeder (`@Profile("demo-realestate")` `RealEstateNurtureDemoSeeder`, "Gateway Realty") | TODO |
| T1.4 | T1 ITs (segmentation / fair-housing / reply-book / analytics) + run new + regression ITs locally; capture counts | TODO |
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
