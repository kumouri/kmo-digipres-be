# PHASE-PROGRESS — T7 Health "RescheduleFlow" (BE leg) (`health-rescheduleflow`)

> Fresh ledger for this branch (off `main` @ `053e465`). Replaces the prior `salon-reviewboost`
> (T6) ledger that occupied this path — that work is already on `main`. Tracks per-sub-phase progress
> + validation status so a resume-after-crash reconstructs the frontier from git + this file, never
> agent memory.

Detail plan: `~/.claude/plans/health-rescheduleflow.md`. Error band **4420–4429** (`GlobalErrorHandler`
Javadoc `<li>` after T6's reserved 4410-4419). Deploys engine **E4** (Gap-Fill Waitlist engine, PR #106)
to the **frontdesk (health) vertical, PHI-free** — E4's first + only consumer (the engine was built FOR
this). Module gate: requires `kmosf.modules.frontdesk` AND `kmosf.modules.waitlist` (compose conditions +
per-tenant `requireEnabled` both — the T2/T4 pattern). **The last Wave-2 tool before GATE 2.**

## Framing (investigation result)

E4 already ships the vertical-agnostic engine + the `SlotMaterializer` SPI (consumer creates its real
domain booking on claim). T7 is the health consumer:
1. A frontdesk `SlotMaterializer` (`key="health-appt"`) creates the real PHI-free `Appointment` on a winning claim.
2. On a frontdesk `Appointment` CANCELLED, build a `WaitlistSlot` + call `GapFillEngine.gapFill`.
3. Patients join the health waitlist (logistics-only prefs) — reuse the generic `WaitlistEntry`.
4. Inbound YES → `WaitlistClaimEngine.claim(...)` via an E2 responder `IntentHandler` (the cleanest reuse).
5. Fill-rate analytics + a read endpoint.

### Cancel signal — investigation result
**There is NO `cancel()` on `AppointmentService`** (status moves via `update(id, patch)` with
`patch.status=CANCELLED`) and **NO `APPOINTMENT_CANCELLED` `DomainEventType`** (only `APPOINTMENT_RISK_SCORED`).
**Decision: ADD it additively** — a new `DomainEventType.APPOINTMENT_CANCELLED` constant + emit from
`AppointmentService.update()` ONLY on a real `SCHEDULED|CONFIRMED → CANCELLED` transition (the additive
`SalonBookingService.cancel()` emit precedent). `AppointmentService` gains a `DomainEventPublisher` dep +
the minimal emit; otherwise empty-diff; all frontdesk ITs stay green.

### Inbound YES — investigation result
**Decision: an E2 responder `IntentHandler`** (`FrontDeskNurtureReplyHandler`/`LogisticsIntentHandler`
precedent) so `InboundSmsService` + the E2 router cores stay empty-diff. Frontdesk-only tenant → chairfill
OFF → `InboundSmsService.claimService==null` → a YES falls through to the E2 `intentRouter` → the T7 handler
→ `WaitlistClaimEngine.claim`. STOP/opt-out honored upstream + the router's `isOptedOut` gate.

## Sub-phases

| # | Sub-phase | Status | Commit | Validation |
|---|-----------|--------|--------|------------|
| T7.1 | Detail plan + this ledger | DONE | 0e821a3 | n/a (docs) |
| T7.2 | `DomainEventType.APPOINTMENT_CANCELLED` (+ T7 advisory events) + minimal additive `AppointmentService` cancel-event emit | DONE | (T7.2 commit) | compileJava OK |
| T7.3 | `module/frontdesk/reschedule/` package — `FrontDeskSlotMaterializer`, `RescheduleGapFillSubscriber`, `RescheduleWaitlistIntentHandler`, `RescheduleAnalyticsService`/`RescheduleFillLog`/repo/`RescheduleFillStats`, `RescheduleController`, `RescheduleFlowAutoConfiguration`; `AutoConfiguration.imports` line; `GlobalErrorHandler` 4420-4429 Javadoc; app-props doc | DONE | (this) | compileJava OK |
| T7.4 | `RescheduleFlowDemoSeeder` (`@Profile("demo-health-reschedule")`) | PENDING | | |
| T7.5 | ITs + regression + openapi regen + CLAUDE.md T7 entry + ledger finalize | PENDING | | |

## Reused-cores empty-diff watchlist (verify `git diff main --stat` at the end)
`service/waitlist/*` (GapFillEngine, WaitlistClaimEngine, WaitlistRankingService, SlotMaterializer,
NoOpSlotMaterializer, WaitlistOfferExpiryService), `model/waitlist/*`, `repository/waitlist/*`,
`controller/waitlist/WaitlistEngineController`, `module/waitlist/WaitlistAutoConfiguration`,
`integration/twilio/{TwilioSmsService,InboundSmsService}`, `service/responder/*`, `model/responder/*`,
`module/chairfill/gapfill/*` (DO NOT TOUCH), `module/frontdesk/model/Appointment`,
`module/frontdesk/model/AppointmentRepository`, `module/frontdesk/controller/AppointmentController`.
ONLY additive frontdesk-core edit: `AppointmentService` (cancel-event emit) + `DomainEventType`.

## Validation log
- T7.1: detail plan + ledger committed.
