# PHASE-PROGRESS — T11 Home "QuoteCloser" (BE leg)

Branch: `home-quotecloser` (off `main` @ 5629e34)
Plan: `~/.claude/plans/home-quotecloser.md`
Module: `module/quoting/closer/` · gate `kmosf.modules.quoting` **AND** `nurture` · error band 4470-4479 · default OFF

The **first Wave-4 composition tool** — composes **three shipped pieces**, builds only the triggers/glue:
**T8 QuoteNow** (the un-accepted `QuoteRequest`/`NEW` signal + `QUOTE_ACCEPTED`) + **E1 Nurture** (the
cadence + the unique-index enroll + the default-OFF runner + the GATE-2 vertical filter) + **E3 Reviews**
(the `ReviewRequest` + the default-OFF sender).

## What each reused piece provides vs T11 net-new (thin by design)

- **T8 quoting** → the source signal: a `NEW` quote that sits un-accepted past a window; `QUOTE_ACCEPTED`
  on accept. T11 adds the **aging sweep** + the **accept subscriber** (zero quoting edit).
- **E1 nurture** → the cadence + the `tenant_campaign_contact_idx` explicit-boolean enroll + the `EXITED`
  terminal stop + the default-OFF `NurtureRunner` that sends the touches. T11 adds the **enroll call** +
  the **EXITED stop call** (zero nurture-core edit; the campaign is tagged `vertical="home"` ⇒ unfiltered).
- **E3 reviews** → the `ReviewRequest` record + creation idempotency + the default-OFF `ReviewRequestSenderJob`
  delivery. T11 adds the **explicit-boolean create** of one `ReviewRequest` on accept (zero E3 edit; the
  shipped sender delivers).

## Sub-phase ledger

- [x] **P0 — detail plan** (`~/.claude/plans/home-quotecloser.md`) + fresh `docs/PHASE-PROGRESS.md`. Commit `6b096ee`.
- [x] **P1 — config** `QuoteCloserConfig` + repo + `QuoteCloserConfigDTO` + `QuoteCloserConfigController` (ADMIN, both-module). 4470/4471.
- [x] **P2 — enrollment job** `QuoteCloserEnrollmentJob` (default-OFF `@Scheduled`): age NEW quotes → explicit-boolean enroll into the campaign; exit enrollments whose quote went non-NEW.
- [x] **P3 — accept subscriber** `QuoteWonSubscriber` (`@PostConstruct` on `QUOTE_ACCEPTED`): stop the cadence (EXITED) + create exactly one `ReviewRequest` (explicit-boolean).
- [x] **P4 — analytics** `QuoteCloserAnalytics` + `QuoteCloserAnalyticsService` + `QuoteCloserController` (`GET .../analytics`).
- [x] **P5 — wiring** `QuoteCloserAutoConfiguration` (both-module gate) + `DomainEventType` T11 block + `GlobalErrorHandler` 4470-4479 Javadoc + app-props doc + `AutoConfiguration.imports`. **`compileJava` PASS.**
- [x] **P6 — demo seed** `QuoteCloserDemoSeeder` (`@Profile("demo-home-quote-closer")`) — "Comfort Air HVAC (QuoteCloser)" + quoting+nurture, a home-vertical QuoteCloser campaign (reminder→financing-nudge→last-call), a 0-hour-window config, + one NEW quote. `compileJava` PASS.
- [x] **P7 — T11 ITs** (enroll 5, won 4, unfiltered-copy GATE-2 proof 1, analytics 2, module-gate 3) — all green (see Validation log).
- [x] **P7b — wiring fix (real bug the regression batch surfaced):** the component-scanned `QuoteCloserController` (`@ConditionalOnProperty(quoting)`) depends on `QuoteCloserAnalyticsService`, but that bean was both-module-gated → context-load failure when quoting ON + nurture OFF. **Fix:** split the read surface into a quoting-only `QuoteCloserReadAutoConfiguration` (wires the analytics service); the both-module `QuoteCloserAutoConfiguration` keeps only the ACTIVE glue (job + subscriber). The both-module requirement is still enforced per-tenant by the controllers' `requireEnabled("quoting")` AND `requireEnabled("nurture")`. `QuoteCloserModuleGateIT.NurtureOff` now asserts the context LOADS (read surface present, active glue absent). All 3 gate contexts green.
- [x] **P8 — regression** green: `nurture.*` (E1 + GATE-2 `BothVerticalsNurtureCopyFilterIT`); `integration.gbp.*` (E3/ReviewBoost); the T8 quoting ITs; `OpenApiEndpointIT`. `openapi.json` UNCHANGED vs main (QuoteCloser endpoints module-gated OFF → absent; FE hand-writes `api/quote-closer.ts`).
- [x] **P9 — docs** (CLAUDE.md T11 entry added after T10) + PR (next).

## Validation log

- `./gradlew compileJava compileTestJava` — PASS (clean; only pre-existing deprecation/unchecked notes).
- `./gradlew test --tests "…closer.QuoteCloserWonIT" --tests "…QuoteCloserAnalyticsIT" --tests "…QuoteCloserModuleGateIT"` — PASS (Won 4/4, Analytics 2/2, ModuleGate 3/3 nested).
- `./gradlew test --tests "…closer.QuoteCloserEnrollmentIT" --tests "…QuoteCloserUnfilteredCopyIT"` — PASS (Enrollment 5/5, UnfilteredCopy 1/1).
- `./gradlew test --tests "…closer.QuoteCloserWonIT" --tests "…QuoteCloserModuleGateIT"` (re-run) — PASS.
- **NOTE — combined `--tests "…closer.*"` in ONE invocation FAILED with `MongoSocketOpenException: Connection refused localhost:13200`** on Won + the NurtureOff gate context — the documented Testcontainers-lifecycle / shared-Mongo isolation artifact (the Mongo container is torn down between Spring contexts; cached contexts then point at a dead port). NOT a logic bug: every class passes in a batch small enough that the shared container stays up. The authoritative gate is the sharded `full-ci.yml`. (See MEMORY: "single-fork `./gradlew test` is Mongo-flaky; cascade timeouts ≠ real failures".)

## Reactive-invariant check

`grep switchIfEmpty module/quoting/closer` MUST show only genuine not-found (config 4470
`switchIfEmpty(Mono.error)`) + the idempotent demo-seeder `switchIfEmpty(seedFresh)`. **Zero
`switchIfEmpty(create/enroll/send)`** — the enroll seam and the review-request seam are BOTH
explicit-boolean (`findBy…().map(true).defaultIfEmpty(false)` + `onErrorResume(DuplicateKeyException)`),
mirroring `TierRoutingService.enrollIfAbsent` + `ReviewRequestService.createIfAbsent`.

## Empty-diff confirmation (reused cores)

`git diff main --stat` MUST show ZERO change to: `QuoteBookingService.java`, `QuoteRequest.java`,
`QuoteRequestRepository.java`, `NurtureRunner.java`, `NurtureMessageComposer.java`,
`NurtureSegmentationService.java`, `NurtureReplyService.java`, `ReviewRequestService.java`,
`ReviewRequest.java`, `ReviewRequestRepository.java`, `ReviewRequestSenderJob.java`, `TwilioSmsService.java`.
The `module/quoting/closer/` package is strictly additive; the only shared-file edits are additive
(`DomainEventType` +3 constants, `GlobalErrorHandler` +Javadoc, `org.springframework.boot.autoconfigure.AutoConfiguration.imports` +1 line, `application.properties` doc).
