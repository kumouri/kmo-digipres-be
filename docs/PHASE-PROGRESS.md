# PHASE-PROGRESS — T14 Home "DispatchIQ" (BE leg) — the FINAL flagship tool

Branch: `home-dispatchiq` (off `main` @ `4435704`)
Plan: `~/.claude/plans/home-dispatchiq.md`
Module: `module/dispatch/` · gate `kmosf.modules.dispatch` (default OFF) · error band **4520-4559**

An **intelligent dispatch optimizer** that proposes the best-fit technician for each open home-services
work order (skill + availability + location/priority) on top of the **EXISTING manual dispatch board** —
a dispatcher reviews the optimized assignments + a rationale + a score, then applies them via the existing
assignment path. **The net-new is the OPTIMIZER, not a new board.** Deterministic/explainable (the T9/T12
scoring precedent — NOT an LLM). Demo collateral, default-OFF. The last of the Wave-2 tools (T1-T14).

## What the shipped code provides vs the T14 net-new

- **Reused (empty-diff):** `DispatchBoardService`/`DispatchBoardController`/`DispatchBoardResponseDTO` (the
  manual board T14 rides on); `WorkOrder` (already has `technicianUserId` — the assignment seam — +
  `serviceType`/`scheduledStart`/`customFields`); `JobSite`+`LatLng` (the stored-coords travel signal);
  `WorkOrderService.update` (the apply path — sets `technicianUserId`); `WorkOrderRepository`;
  `ProjectAssignmentService` + `ServiceAgreementSchedulerService` (read end-to-end; T14 touches neither);
  `UserRepository.findAllByTenantIdAndPortal` (the tech directory).
- **Net-new (`module/dispatch/`):** the deterministic `DispatchOptimizerService` (skill + availability +
  proximity + priority composite → greedy priority-first assignment with rationale/score), the
  `DispatchPlanService` orchestrator (optimize on `boundedElastic` + apply via the reused
  `WorkOrderService.update`), `DispatchAnalyticsService`, the `DispatchController`
  (`GET /dispatch/optimize`, `POST /dispatch/apply` `@IdempotentRoute`, `GET /dispatch/analytics`), the
  model records, the `DispatchAutoConfiguration`, the `DispatchDemoSeeder`.
- **The lone reused-model seam:** additive-nullable **`User.skills` (`List<String>`)** — the soft
  skill-fit signal (the T12 `StaffMember.specialties` precedent; `UserService`/`TeamController`
  byte-identical; legacy users deserialize empty = no declared skills = neutral, never excluded).

## Sub-phase ledger

| # | Sub-phase | Status | Notes |
|---|---|---|---|
| 1 | Detail plan + this ledger | ✅ done | commit 1 (1b2c352) |
| 2 | Model + scorer: `User.skills` additive field, `module/dispatch/model/*` records, the pure `DispatchOptimizerService` (+ no-Docker unit test), `DomainEventType` T14 block | ✅ done | commit 2 (85e85e8); `DispatchOptimizerServiceTest` 9/9 |
| 3 | Orchestrator + surface: `DispatchPlanService` (optimize + apply via reused `WorkOrderService.update`), `DispatchAnalyticsService`, `DispatchController`+DTOs, `DispatchAutoConfiguration`, `AutoConfiguration.imports`, `GlobalErrorHandler` 4520-4559 Javadoc, `DispatchDemoSeeder` | ✅ done | commit 3 (61385ad) |
| 4 | Tests + docs: `DispatchOptimizeIT`/`DispatchApplyIT`/`DispatchAnalyticsIT`/`DispatchModuleGateIT`, `CLAUDE.md` T14 entry (final-tool note), app-props doc; run + record regression | ✅ done | commit 4 |
| 5 | Push + ready PR | ⬜ pending | |

## Result — T14 tests (validated via clean local IT re-run; full-ci OOM-impaired)
- New T14 (21 tests, 0 failures): `DispatchOptimizerServiceTest` 9 (no-Docker — the optimizer/assignment math proof) · `DispatchOptimizeIT` 2 (skill-matched + priority-first + unstaffable→unassigned) · `DispatchApplyIT` 5 (apply commits + **re-apply idempotent no double-assign** + 4523/4520/4522/3100) · `DispatchAnalyticsIT` 2 · `DispatchModuleGateIT` 3 (beans absent + authed routes 401 when OFF).
- Regression (all green): `module.homeservices.*` (29 classes / 85 — incl. `DispatchBoardIT`, the board T14 rides on) · `contractor.*` (7 classes / 56 — `ProjectAssignmentService`/Phase-J empty-diff) · `openapi.OpenApiEndpointIT` 2 (boot guard + **`openapi.json` unchanged** = default-OFF dispatch endpoints absent from the spec, the T1-T13 precedent).
- Reused cores empty-diff verified vs `main` (0 lines each): `DispatchBoardService`, `DispatchBoardController`, `DispatchBoardResponseDTO`, `WorkOrder`, `JobSite`, `WorkOrderService`, `WorkOrderRepository`, `ProjectAssignmentService`, `ServiceAgreementSchedulerService`. The lone reused-model edit is the additive `User.skills`.
- Reactive invariant: `switchIfEmpty` only for genuine not-found (4520) + the idempotent seed-defer; the apply already-assigned seam is the explicit-boolean `Objects.equals` skip (NO `switchIfEmpty(create/assign)`); the optimizer compute + the apply batch run on `Schedulers.boundedElastic()`.

## Fixture/code adjustments during sub-phase 4 (recorded)
- **Apply not-found code:** the reused `WorkOrderService.findById` throws its own `1330` before an outer `switchIfEmpty(4520)` could fire, so the apply load now uses the tenant-scoped `WorkOrderRepository.findById` (returns empty, not a throw) → the dispatch-local `4520` is the apply-decision not-found code. `WorkOrderService.update` is still the reused assignment path (empty-diff preserved).
- **Dispatcher ≠ field tech:** a no-declared-skills STAFF user is a valid generalist fallback candidate (proven by `noDeclaredSkillsTech_isEligibleFallback`), so the office dispatcher/admin account is given a declared non-field skill (`"DISPATCH"`) in the ITs + the demo seed → it stays out of the field-tech candidate pool, making the "unstaffable job → unassigned" property deterministic.

## Decisions / deviations

- **D1 — reuse the board, optimize on top.** T14 is NOT a new board. The optimizer proposes
  `technicianUserId` assignments; apply commits them through the **unchanged** `WorkOrderService.update`;
  the **unchanged** `DispatchBoardService` re-renders them. Zero board/board-DTO change.
- **D2 — `User.skills` is the one justified seam.** Skill-fit is the optimizer's headline signal and
  `User` had no skills field. Additive-nullable `List<String>` (the T12 `StaffMember.specialties`
  precedent). `UserService`/`TeamController` empty-diff (skill editing is an out-of-scope additive
  follow-up — the T12 posture). No additive field on `WorkOrder` (it already has `technicianUserId` +
  `serviceType`).
- **D3 — availability = load, no new field.** A tech's availability component is `1 - load/cap` over their
  current same-day assigned-work-order count (read from the loaded set) — load-balancing without inventing
  an availability/shift model on `User`.
- **D4 — deterministic, not an LLM.** The composite (skillFit 0.45 + availability 0.30 + proximity 0.15 +
  priority 0.10) + the greedy priority-first assigner is pure/total/stateless (the
  `StylerMatchScoringService` / `StyleRecommendationService` precedent). No AI budget, no vision, no SMS.
- **D5 — apply idempotency = explicit-boolean already-assigned skip + `@IdempotentRoute`.** Re-applying the
  same decisions sets each `WorkOrder.technicianUserId` to its target; a work order already at the target is
  **skipped** (no save/event) → no double-assign. `switchIfEmpty` only for genuine not-found (4520). The
  optimizer compute + the apply batch run on `boundedElastic`.
- **D6 — error band 4520-4559.** 4520 WO-not-found (apply); 4521 invalid optimize req; 4522 invalid apply
  req; 4523 WO not in assignable state (terminal); **4524-4559 RESERVED** (the wide band — multi-day,
  hard time-windows, skill-cert gates, real routing-API travel). Reused: 1130/1132, 1800, 1330, 3100/3101.
- **D7 — default-OFF; not in openapi.** `@ConditionalOnProperty(kmosf.modules.dispatch)` (no
  `matchIfMissing`) + `@ConditionalOnBean(WorkOrderService.class)` (the apply path needs the field-service
  WorkOrder spine). `OpenApiEndpointIT` runs with it OFF → no `dispatch` paths in `openapi.json` → the FE
  hand-writes all `dispatch` `api/*.ts` (the T1-T13 precedent).
- **D8 — go-live: a real routing/maps API** for true drive-time travel (T14 uses the board's
  `JobSite.location` haversine straight-line proxy; the proximity component is one method to swap). No live
  maps/routing API in the loop.

## Validation gate

Per the briefing, full-ci is OOM-impaired pending larger runners → the gate is a **clean local IT re-run**
of the new T14 ITs + the regression groups (`module.homeservices.*`, `service.contractor.*`,
`OpenApiEndpointIT`), recorded in the final report. The PR opens READY; the orchestrator validates
independently + merges.

## Reactive invariant

`switchIfEmpty` only for genuine not-found (4520 / the reused `WorkOrderService.findById` 1330). The apply
already-assigned seam is an explicit-boolean skip — **NEVER `switchIfEmpty(assign)`**. The optimizer compute
+ the apply batch run on `Schedulers.boundedElastic()` (never the Netty event loop). No live external in the
build loop.
