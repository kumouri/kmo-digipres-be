# PHASE-PROGRESS — T12 Salon "StylerMatch" (BE leg)

Branch: `salon-stylermatch` (off `main` @ `ee257d3`)
Plan: `~/.claude/plans/salon-stylermatch.md`
Module: **new `module/stylermatch/`** · rides the **`chairfill`** salon-flagship key (the T9 StyleConsult posture) · error band **4480-4489** · default OFF

Match a new client's **requested service/style** → the **best-fit stylist** (specialty fit + availability + past-preference) → a **ranked, explained** match (deterministic, NOT an LLM call) → **book** via the UNCHANGED `SalonBookingService.create`. The stylist-side twin of T9 StyleConsult (which ranks services + retail).

## The one justified model seam — `StaffMember.specialties`

`StaffMember.eligibleServiceIds` is a HARD service-id eligibility constraint (empty = any), not a free-text style/skill signal. T12 adds ONE strictly-additive, nullable `List<String> specialties` (`@Builder.Default = List.of()`, the `Product.unitCost`/`Booking.noShowRisk` additive-nullable precedent — legacy docs read empty = no declared specialty, ranked-not-excluded). **`StaffMemberService` stays byte-identical** — it never references the new field (no `specialties` merge added; keeping the service empty-diff is the stronger reuse guarantee; specialties are seeded + read by StylerMatch, staff-edit is an out-of-scope additive follow-up). This is THE one allowed seam on a reused model.

## Sub-phase ledger

- [x] **P0 — detail plan** (`~/.claude/plans/salon-stylermatch.md`) + fresh `docs/PHASE-PROGRESS.md` (overwrites the stale T11 ledger on `main`). Commit `cfee721`.
- [x] **P1 — the `StaffMember.specialties` seam + `StylerMatchScoringService`** (pure/deterministic/explainable; the marquee). Weighted specialty-fit + eligibility + availability (window coverage + read-only slot-conflict) + preference (explicit + prior-COMPLETED-visit); ranked + rationale + confidence; central `STYLIST_CONFIRM_NOTE`. `StaffMemberService` UNCHANGED. `MatchRequest`/`RankedMatch`. **`StylerMatchScoringServiceTest` 11/11 green (pure, no Docker).** Commit `5dcb384`.
- [x] **P2 — match intake + ranked result.** `StylerMatch` entity + `StylerMatchStatus` + repo + `StylerMatchService` orchestrator (public-token + staff-desk; find-or-create contact explicit-boolean) + `StylerMatchIntakeController` (public JSON, token; strips client `contactId`) + `StylerMatchController` (staff create/inbox) + `StylerMatchTokenController` (ADMIN mint) + `StylerMatchResponse`/`StylerMatchRequestBody`. No multipart/AI.
- [x] **P3 — match→booking funnel.** `StylerMatchBookingService.accept` (explicit-boolean idempotent `bookingId != null`; UNCHANGED `SalonBookingService.create`; `BookingPolicyService` enforces the hard eligibility/availability at book time; stamps `selectedRank`) + `StylerMatchAcceptController` (public, no `@IdempotentRoute`).
- [x] **P4 — match analytics.** `StylerMatchAnalyticsService` accept-rate-**by-rank** funnel (which rank got booked) + `StylerMatchAnalytics` DTO + `GET /stylermatch/analytics`.
- [x] **P5 — wiring.** `StylerMatchAutoConfiguration` (chairfill + `@ConditionalOnBean(SalonBookingService)`) + `DomainEventType` T12 block (STYLER_MATCH_REQUESTED/BOOKED) + `GlobalErrorHandler` 4480-4489 Javadoc + `AutoConfiguration.imports` + app-props doc. **`compileJava` PASS.** Empty-diff + reactive-invariant verified (below).
- [x] **P6 — demo seed.** `StylerMatchDemoSeeder` (`@Profile("demo-salon-stylermatch")`) — "Shear Brilliance Studio" + salon-spa+chairfill + a service menu + 4 stylists (Maya balayage/curly all-eligible Tue-Sat; Jordan blonde/highlights all-eligible Wed-Sun; Sam cut/keratin NOT-color-eligible Mon-Fri; Riley no-specialty versatile Mon-Sat) + a sandbox bookingLink. `compileJava` PASS.
- [x] **P7 — T12 ITs (all green, Docker).** `StylerMatchIntakeIT` (5), `StylerMatchAcceptIT` (5), `StylerMatchAnalyticsIT` (4), `StylerMatchModuleGateIT` (2) + the pure `StylerMatchScoringServiceTest` (11). **Regression all green:** `module.chairfill.*` (8 classes / 50 — these exercise the salon-spa `SalonBookingService`/`Booking`/`ServiceMenu`/`StaffMember` cores) + `OpenApiEndpointIT` (2). No standalone `module.salonspa.*` IT package exists (the salon-spa cores are regression-covered via `module.chairfill.*`).
- [x] **P8 — docs + PR.** CLAUDE.md T12 entry added (after T11, in the Wave-4 section + the band note); PR opened ready.

## Validation log
- `./gradlew compileJava` (P1) — PASS (clean; only pre-existing deprecation/unchecked notes).
- `./gradlew test --tests "…stylermatch.StylerMatchScoringServiceTest"` (P1) — **PASS 11/11** (the ranking proof: specialty-fit ranks right; availability conflict demotes; window coverage outranks; eligibility miss penalized-but-visible; preference bump; explicit-preferred largest; deterministic across runs; empty-specialties ranked-not-excluded; confidence; guardrail on every rationale).
- `./gradlew compileJava` (P2-P5) — PASS (clean).
- **Empty-diff (reused cores) — VERIFIED EMPTY** vs `main`: `SalonBookingService`, `BookingPolicyService`, `Booking`, `ServiceMenu`, `ServiceMenuItem`, `SalonMenuService`, `StaffMemberService`, `PublicWidgetTokenService`, all `module/chairfill/*`, all `module/styleconsult/*`. Only reused-model edit = `StaffMember.java` (+16, the additive `specialties` seam).
- **Reactive-invariant — VERIFIED**: the only `switchIfEmpty` code in `module/stylermatch` is 2× genuine not-found (`Mono.error` 4485) + 1× find-fallback (`byPhone.switchIfEmpty(byEmail)`). Find-or-create create branch is explicit-boolean (`Optional`); accept idempotency is explicit-boolean (`bookingId != null`). **Zero `switchIfEmpty(create/book)`.**
- `./gradlew test --tests "…stylermatch.StylerMatchModuleGateIT" --tests "…StylerMatchIntakeIT"` — **PASS** (ModuleGate 2/2, Intake 5/5).
- `./gradlew test --tests "…stylermatch.StylerMatchAcceptIT" --tests "…StylerMatchAnalyticsIT"` — **PASS** (Accept 5/5 incl. the hard-eligibility-reject-at-book-time 2900 proof + idempotent re-accept; Analytics 4/4 incl. the accept-rate-by-rank funnel).
- `./gradlew test --tests "…chairfill.NoShowRiskScoringIT" "…RiskTieredPreventionIT" "…GapFillWaitlistIT" "…WaitlistBoardIT"` (regression batch 1) — **PASS** (8/5/11/6 = 30).
- `./gradlew test --tests "…chairfill.OfferExpirySweepIT" "…SalonReviewBoostConfigIT" "…SalonReviewBoostInsightsIT" "…SalonReviewReplyIT" "…openapi.OpenApiEndpointIT"` (regression batch 2) — **PASS** (3/4/5/8 + OpenApi 2/2).
- **`openapi.json` UNCHANGED vs `main`** (verified empty-diff; T12 endpoints module-gated OFF → absent → the FE hand-writes ALL `stylermatch` `api/*.ts`, the T1-T11 precedent).
- ITs run in small batches per the shared-Mongo Testcontainers-lifecycle note (single-fork large batches are Mongo-flaky); the authoritative gate is the sharded `full-ci.yml`.

## New endpoints (for the FE leg — all default-OFF, absent from openapi.json)
- `POST /public/integrations/stylermatch/{token}/match` (public widget, JSON `StylerMatchRequestBody`, token `styler-match`) → `StylerMatchResponse` (ranked board).
- `POST /public/integrations/stylermatch/{token}/matches/{matchId}/accept?staffMemberId=` (public, no `@IdempotentRoute`) → `StylerMatchResponse` (BOOKED).
- `POST /stylermatch/matches` (staff, 201, `StylerMatchRequestBody`) → `StylerMatchResponse`.
- `GET /stylermatch/matches` (staff) → `StylerMatchResponse[]` (inbox, newest first).
- `GET /stylermatch/matches/{id}` (staff) → `StylerMatchResponse` (4485 if absent).
- `GET /stylermatch/analytics` (staff) → `StylerMatchAnalytics` (accept-rate-by-rank funnel).
- `POST /stylermatch/tokens` (ADMIN, 201) → `{"token": "..."}` (mints the `styler-match` widget token).

## Reactive-invariant check
`grep switchIfEmpty module/stylermatch` MUST show only genuine not-found (`switchIfEmpty(Mono.error(...))`, codes 4480/4485) + the idempotent demo-seeder `switchIfEmpty(seedFresh)`. **Zero `switchIfEmpty(create/book)`** — the accept idempotency seam is explicit-boolean (`bookingId != null`).

## Empty-diff confirmation (reused cores)
`git diff main --stat` MUST show ZERO change to: `SalonBookingService.java`, `BookingPolicyService.java`, `Booking.java`, `ServiceMenu.java`, `ServiceMenuItem.java`, `SalonMenuService.java`, `StaffMemberService.java`, `PublicWidgetTokenService.java`, `TwilioSmsService.java`, all `module/chairfill/*`, all `module/styleconsult/*`. The ONLY reused-model edit is the additive `StaffMember.specialties` field (justified above). The `module/stylermatch/` package is strictly additive; the only other shared-file edits are additive (`DomainEventType` +2 constants, `GlobalErrorHandler` +Javadoc, `AutoConfiguration.imports` +1 line, `application.properties` doc).

## Error band 4480-4489
4480 token widgetType mismatch (401) · 4481 invalid match request (400) · 4482 no active stylists (404) · 4483 no rankable stylist to book (404) · 4484 RESERVED · 4485 match not found (404) · 4486 match not acceptable-state (409, RESERVED-advisory) · 4487-4489 RESERVED. Reused: 1600-1603 (token), 2900/2901 (salon Booking eligibility/availability via the unchanged services), 2530-2532 (SMS), 1130/1132 (module gate), 1800 (ADMIN).
