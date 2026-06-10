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

- [ ] **P0 — detail plan** (`~/.claude/plans/home-quotecloser.md`) + fresh `docs/PHASE-PROGRESS.md` (this commit).
- [ ] **P1 — config** `QuoteCloserConfig` + repo + `QuoteCloserConfigController` (ADMIN, both-module). 4470/4471.
- [ ] **P2 — enrollment job** `QuoteCloserEnrollmentJob` (default-OFF `@Scheduled`): age NEW quotes → explicit-boolean enroll into the campaign; exit enrollments whose quote went non-NEW.
- [ ] **P3 — accept subscriber** `QuoteWonSubscriber` (`@PostConstruct` on `QUOTE_ACCEPTED`): stop the cadence (EXITED) + create exactly one `ReviewRequest` (explicit-boolean).
- [ ] **P4 — analytics** `QuoteCloserAnalyticsService` + `QuoteCloserController` (`GET .../analytics`).
- [ ] **P5 — wiring** `QuoteCloserAutoConfiguration` (both-module gate) + `DomainEventType` T11 block + `GlobalErrorHandler` 4470-4479 Javadoc + app-props doc + `AutoConfiguration.imports`.
- [ ] **P6 — demo seed** `QuoteCloserDemoSeeder` (`@Profile("demo-home-quote-closer")`).
- [ ] **P7 — T11 ITs** (enroll, stop, review, unfiltered-copy GATE-2 proof, analytics, module gate).
- [ ] **P8 — regression** (`module.quoting.*`, `nurture.*` incl. `BothVerticalsNurtureCopyFilterIT`, `integration.gbp.*`, `OpenApiEndpointIT`) green.
- [ ] **P9 — docs** (CLAUDE.md T11 entry) + PR.

## Validation log

(filled as sub-phases complete)

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
