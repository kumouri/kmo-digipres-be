# PHASE-PROGRESS — T12 Salon "StylerMatch" (BE leg)

Branch: `salon-stylermatch` (off `main` @ `ee257d3`)
Plan: `~/.claude/plans/salon-stylermatch.md`
Module: **new `module/stylermatch/`** · rides the **`chairfill`** salon-flagship key (the T9 StyleConsult posture) · error band **4480-4489** · default OFF

Match a new client's **requested service/style** → the **best-fit stylist** (specialty fit + availability + past-preference) → a **ranked, explained** match (deterministic, NOT an LLM call) → **book** via the UNCHANGED `SalonBookingService.create`. The stylist-side twin of T9 StyleConsult (which ranks services + retail).

## The one justified model seam — `StaffMember.specialties`

`StaffMember.eligibleServiceIds` is a HARD service-id eligibility constraint (empty = any), not a free-text style/skill signal. T12 adds ONE strictly-additive, nullable `List<String> specialties` (`@Builder.Default = List.of()`, the `Product.unitCost`/`Booking.noShowRisk` additive-nullable precedent — legacy docs read empty = no declared specialty, ranked-not-excluded). **`StaffMemberService` stays byte-identical** — it never references the new field (no `specialties` merge added; keeping the service empty-diff is the stronger reuse guarantee; specialties are seeded + read by StylerMatch, staff-edit is an out-of-scope additive follow-up). This is THE one allowed seam on a reused model.

## Sub-phase ledger

- [ ] **P0 — detail plan** (`~/.claude/plans/salon-stylermatch.md`) + fresh `docs/PHASE-PROGRESS.md` (overwrites the stale T11 ledger on `main`).
- [ ] **P1 — the `StaffMember.specialties` seam + `StylerMatchScoringService`** (pure/deterministic/explainable; the marquee). Weighted specialty-fit + eligibility + availability + preference; ranked + rationale + confidence; central `STYLIST_CONFIRM_NOTE`. `StaffMemberService` UNCHANGED.
- [ ] **P2 — match intake + ranked result.** `StylerMatch` entity + `StylerMatchService` orchestrator + public widget intake (JSON, token) + staff create/inbox + ADMIN token mint. No multipart/AI (text match).
- [ ] **P3 — match→booking funnel.** `StylerMatchBookingService.accept` (explicit-boolean idempotent; UNCHANGED `SalonBookingService.create`; `BookingPolicyService` enforces the hard eligibility/availability at book time) + public accept controller (no `@IdempotentRoute`).
- [ ] **P4 — match analytics.** accept-rate-**by-rank** funnel (which rank got booked) + `GET /stylermatch/analytics`.
- [ ] **P5 — wiring.** `StylerMatchAutoConfiguration` (chairfill + `@ConditionalOnBean(SalonBookingService)`) + `DomainEventType` T12 block + `GlobalErrorHandler` 4480-4489 Javadoc + `AutoConfiguration.imports` + app-props doc.
- [ ] **P6 — demo seed.** `StylerMatchDemoSeeder` (`@Profile("demo-salon-stylermatch")`) — "Shear Brilliance Studio" + stylists w/ varied specialties/availability/eligibility + a service menu + bookingLink.
- [ ] **P7 — T12 tests.** `StylerMatchScoringServiceTest` (pure, no Docker — the ranking proof) + intake/accept/analytics/module-gate ITs. Reactive-invariant grep. Regression: `module.salonspa.*` + `module.chairfill.*` + `OpenApiEndpointIT`.
- [ ] **P8 — docs + PR.** CLAUDE.md T12 entry; PR ready.

## Validation log
(filled as sub-phases land)

## Reactive-invariant check
`grep switchIfEmpty module/stylermatch` MUST show only genuine not-found (`switchIfEmpty(Mono.error(...))`, codes 4480/4485) + the idempotent demo-seeder `switchIfEmpty(seedFresh)`. **Zero `switchIfEmpty(create/book)`** — the accept idempotency seam is explicit-boolean (`bookingId != null`).

## Empty-diff confirmation (reused cores)
`git diff main --stat` MUST show ZERO change to: `SalonBookingService.java`, `BookingPolicyService.java`, `Booking.java`, `ServiceMenu.java`, `ServiceMenuItem.java`, `SalonMenuService.java`, `StaffMemberService.java`, `PublicWidgetTokenService.java`, `TwilioSmsService.java`, all `module/chairfill/*`, all `module/styleconsult/*`. The ONLY reused-model edit is the additive `StaffMember.specialties` field (justified above). The `module/stylermatch/` package is strictly additive; the only other shared-file edits are additive (`DomainEventType` +2 constants, `GlobalErrorHandler` +Javadoc, `AutoConfiguration.imports` +1 line, `application.properties` doc).

## Error band 4480-4489
4480 token widgetType mismatch (401) · 4481 invalid match request (400) · 4482 no active stylists (404) · 4483 no rankable stylist to book (404) · 4484 RESERVED · 4485 match not found (404) · 4486 match not acceptable-state (409, RESERVED-advisory) · 4487-4489 RESERVED. Reused: 1600-1603 (token), 2900/2901 (salon Booking eligibility/availability via the unchanged services), 2530-2532 (SMS), 1130/1132 (module gate), 1800 (ADMIN).
