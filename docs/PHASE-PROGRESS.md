# Phase E — Billing, Recurring & Stripe — Progress Ledger

> This file is the crash-recovery source of truth per ultraplan §6 Resilience.
> Each commit is recorded as it lands. The plan §10 stub mirrors this.
> Plan: `back-office-kmo-digipres-phase-E-billing-recurring-stripe.md`

## Branch: `back-office-kmo-digipres-phase-E-billing-recurring-stripe`

| Sub-phase | Status | SHA | Notes |
|---|---|---|---|
| E.1 — error range 3600-3699 + DomainEventType Phase-E block + Invoice.PaymentTerms (additive) | done | 4e3b71f | compileJava clean; full test 399/0/0 — no regression, no error-code collision |
| E.2 — Quartz Mongo JobStore dep + QuartzConfig customizer + properties | done | 15d90d0 | E-D5 RAM-FALLBACK ACTIVE — see decision below; QuartzMongoJobStoreIT 2/0/0 (RAM store, proof job fires) |
| E.3 — RecurringInvoice + RecurringInvoiceOccurrence + repos | done | 6066774 | E-D2 field tables exact; unique tenant_recurring_period_idx; full test 399/0/0 (entities map clean, index auto-creates) |
| E.4 — RecurringInvoiceService + RecurringInvoiceSpawnService + Quartz job | done | 69619bc | ledger-insert-FIRST + explicit-boolean probe; self-grep clean (only 3605 not-found switchIfEmpty); full test 399/0/0 |
| E.5 — StripeWebhookEvent + extended StripeWebhookService + StripeProperties + StripeCheckoutService + controllers | done | 576b7d3 | event-id idempotency (ledger-first, 200-no-op dup) + INVOICE_PAID; @IdempotentRoute×4; QuickBooksInvoiceSync untouched; self-grep clean; full test 399/0/0 |
| E.6 — BE ITs AC-E1…AC-E8 | done | 019fb3f | full suite 422/0/0 + 2 skipped (399 main, no regression, +23 Phase-E). **BLOCKER: AC-E3 full catch-up blocked by pre-existing non-sparse Invoice tenant_number_idx — see below** |
| E.7 — BE CLAUDE.md in-PR + .claude/* local + docs/api/openapi.json committed | done | 697f5e8 | CLAUDE.md Phase E SHIPPED note + blocker; .claude/* local (gitignored); openapi.json 164 paths/126 schemas |
| E.8 — Final BE green + PR | done | 484405b | full build 422/0/0 + 2 skipped; verifyOpenApi OK; §9 self-grep clean (3 hits all genuine not-found); no-live-money sweep clean; PR #55 opened — NOT merged (separate Opus validates) |
| E.9 — InvoiceNumberGenerator (service.billing) — blocker resolution | done | 41d7787 | clone of ProjectCodeGenerator: atomic Mongo $inc upsert on invoice_number_counters keyed tenantId:year; INV-%d-%04d; UTC year; switchIfEmpty→3640 (defensive, mirrors 3433); error code 3640 added to GlobalErrorHandler Javadoc; compileJava clean |
| E.10 — Wire InvoiceNumberGenerator into InvoiceService finalize edge | done | 07f8eba | assignNumberIfIssued(inv,target) explicit-boolean (number==null && target∈{SENT,PARTIALLY_PAID,PAID,OVERDUE}); wired into setStatus + createFromQuote + refreshInvoiceStatus auto-advance; saveWithNumberRetry DuplicateKey backstop (ProjectService C-D3 mirror, 3640 on 2nd collision); VOIDED excluded; create/recordPayment/recompute/maybePublishFinalized semantics intact; compileJava clean — AUTHORIZED §7 override |
| E.11 — Migrate tenant_number_idx to partial-unique | done | 545341f | @CompoundIndex removed from Invoice.java (index owned fully by InvoiceNumberIndexInitializer); idempotent ApplicationReadyEvent initializer (QuartzBootstrap pattern): reconciles via getIndexInfo → drop-if-not-desired-partial-unique, create partial-unique {tenantId:1,invoiceNumber:1} partialFilterExpression {invoiceNumber:{$type:"string"}} (the MongoDB-legal "present & non-null"; $ne:null is NOT a legal pfe operator, bare $exists:true still matches BSON-null DRAFTs). Safe+idempotent: absent→create / present-but-wrong→drop+create / present-correct→no-op. .block() on startup thread; failure logged+swallowed (counter is primary, index is backstop). compileJava clean |
| E.12 — Re-enable + extend tests | done | (this branch) | un-@Disabled the 2 AC-E3 full-catch-up specs in RecurringInvoiceSpawnRestartIT (replaced obsolete currentBehavior_* blocker doc-test); added InvoiceNumberGeneratorIT (mirrors ProjectCodeGeneratorIT) — DRAFT→SENT-numbered-once + idempotent, partial-index permits N null DRAFTs / rejects dup number, AC-E2 ≥3-period recurring multi-spawn; InvoiceFinalizedEmissionTest extended. Full suite GREEN, 0 skipped from the AC-E3 pair, no main regression. **Catch-up nondeterminism root-caused — see section below** |
| E.13 — Catch-up root-cause + flake fixes + final docs | done | (this branch) | Conclusively diagnosed the RecurringInvoiceSpawnRestartIT flake as a TEST-isolation artifact (cross-tenant runDueOnce + Spring-context-cached siblings on ONE shared Testcontainers Mongo) — NOT a RecurringInvoiceSpawnService over-spawn/double-bill bug. Money-safe test-only fixes (zero production change): fixed @Primary Clock, ledger-scoped + foreign-tick-immune money-invariant assertions, global test spawn-job disable. Pre-existing ServiceAgreementSchedulerIT date-flake fixed the in-repo way (fixed @Primary Clock, NOT a Phase-E change). CLAUDE.md + this ledger finalized; openapi.json unchanged (no API surface change since E.7) |

## Quartz-store resolution decision (SETTLED at E.2 — before E.4, as required)
- [ ] `io.fluidsonic.mirror:quartz-mongodb:2.2.0-rc2` resolved + context boots with Mongo store → Mongo store active
- [x] **E-D5 RAM-FALLBACK ACTIVE.** The coordinate **does resolve** from Maven Central and the
      Mongo store **does load** (`com.novemberain.quartz.mongodb.MongoDBJobStore` +
      `CheckinExecutor` start), but it was compiled against Quartz 2.3.2 and Spring Boot
      3.5.6 manages **Quartz 2.5.0**, which removed `JobDetail.isConcurrentExectionDisallowed()`.
      Every trigger fire throws `java.lang.NoSuchMethodError` at
      `com.novemberain.quartz.mongodb.LockManager.lockJob(LockManager.java:31)` →
      **no Quartz job ever executes under the Mongo store**. This is the plan §9 item 1 /
      E-D5 documented STOP-and-fallback condition (the plan pre-authorizes the fallback and
      explicitly does not block on the dependency). Pinning Quartz to 2.3.2 would fight the
      Boot BOM and risk the 8 shipped @Scheduled/Quartz services (R3); the fluidsonic mirror
      has no Quartz-2.5-compatible release.
      - **Fallback:** `kmosf.quartz.store=memory` (default). Dependency intentionally NOT
        added to build.gradle (non-functional dead weight; would also drag
        mongodb-driver-sync:4.0.5 onto the classpath). `QuartzConfig` mongo branch + the
        flag stay wired and ready for the day a Quartz-2.5-compatible quartz-mongodb appears.
      - **Money-correctness preserved:** durability of "which periods were spawned" lives in
        the `RecurringInvoiceOccurrence` unique-indexed Mongo ledger + `RecurringInvoice.nextRunAt`
        cursor (E-D2/E-D3). A stateless RAM-store trigger re-running `runDueOnce()` hourly
        reconciles from that ledger every tick (bounded catch-up) — the
        ServiceAgreementSchedulerService model. **`RecurringInvoiceSpawnRestartIT` (E.6) is
        the durability proof.** `QuartzMongoJobStoreIT` (2/0/0) documents the fallback + that
        the proof job still fires under the RAM store.
      - Decision recorded in: `build.gradle`, `application.properties`, this file,
        `kmo-digipres-be/CLAUDE.md` (E.7), and the plan §10 stub (E.8).

## ESCALATED BLOCKER — ✓ RESOLVED (user-authorized §7 override, E.9–E.11)

> **STATUS: RESOLVED.** The user authorized the combined correct fix (overriding
> plan §7's numbering non-goal as an explicit, recorded scope change — see
> `kmo-digipres-be/CLAUDE.md` the ✓ RESOLVED bullet). Implemented in E.9
> (`InvoiceNumberGenerator`, finalize-time `INV-{YYYY}-{NNNN}`), E.10 (assigned at
> the DRAFT→issued edge in `InvoiceService`, explicit-boolean idempotent), E.11
> (`tenant_number_idx` migrated to **partial-unique** via the idempotent
> `InvoiceNumberIndexInitializer`; `@CompoundIndex` removed from `Invoice.java`).
> Many null-numbered DRAFTs per tenant are now legal ⇒ recurring multi-period
> one-tick catch-up works; the 2 AC-E3 specs are re-enabled and GREEN (E.12). The
> money invariants (ledger-insert-FIRST + compensating delete: zero double-bill /
> orphan / loss-after-success / merged lump) still hold. The original analysis is
> retained verbatim below for the audit trail.

**Plan §7 ("recurring-spawned invoices leave invoiceNumber null exactly as
create/milestone/time paths do") is factually incompatible with the pre-existing
non-sparse unique index on `Invoice`:**

`@CompoundIndex(name="tenant_number_idx", def="{'tenantId':1,'invoiceNumber':1}", unique=true)`
— verified in a running Mongo: `{key:{tenantId:1,invoiceNumber:1}, unique:true}`,
**NO sparse, NO partialFilterExpression**. Inserting two `invoiceNumber==null`
invoices for the same tenant → `E11000 duplicate key`. **A tenant can hold at most
ONE null-invoiceNumber invoice, ever.** Every single-invoice path
(milestone/time/expense/Square) gets away with one null per test; recurring
billing is the first design that needs N null-numbered invoices per tenant, so the
2nd+ recurring period's `invoiceService.create` E11000s on every tick — full
single-tick catch-up (AC-E3) and the AC-E2 "spawn a 2nd period" cannot work as
written.

The plan §7 ALSO forbids both a numbering change and an `Invoice`
index/migration, so the implementer cannot resolve this without an out-of-scope
decision (per the briefing: never improvise around §7 non-goals; STOP + escalate).

**Money-correctness is NOT compromised by what shipped** — the
`RecurringInvoiceOccurrence` ledger-insert-FIRST + a new compensating delete in
`doSpawn` (a post-insert `invoiceService.create` failure deletes the just-inserted
ledger row) guarantee: ZERO double-bill, ZERO orphan/partial ledger rows, ZERO
merged-lump invoice, the spawned period billed exactly once. The gap is purely
"the 2nd+ recurring period for a tenant is never billed until the index is
resolved" (blocked, not corrupted, not lost-after-success, not double-charged).

**Resolution options (for the validator/user — all currently out of the
implementer's §7 scope):**
1. Make `tenant_number_idx` a **partial** unique index
   (`partialFilterExpression: {invoiceNumber: {$type:"string"}}`) — uniqueness still
   enforced for real numbers; nulls no longer collide. Smallest change; arguably a
   pre-existing-defect fix; but it IS an index/migration change (§7 non-goal).
2. Assign recurring-spawned invoices an opaque non-sequential unique discriminator
   (e.g. `REC-{recurringInvoiceId}-{periodKey}`) into `invoiceNumber` — a "numbering"
   change (§7 non-goal) but localized to the recurring path.
3. Re-scope AC-E2/AC-E3 to "one recurring invoice per tenant per cadence" pending a
   future numbering phase.

Resolution shipped (option 1 + finalize-time numbering, user-authorized): AC-E1…E8
green; AC-E2 multi-period + AC-E3 full one-tick catch-up specs RE-ENABLED and green
in `RecurringInvoiceSpawnRestartIT` (the obsolete `currentBehavior_*` blocker
doc-test was removed); `InvoiceNumberGeneratorIT` proves the numbering + partial
index.

## Catch-up nondeterminism root-cause — RESOLVED (E.13)

`RecurringInvoiceSpawnRestartIT.manyMissedPeriods` intermittently flipped
green→red across identical full-suite runs (`was 5`; after the first fix round a
residual `occurrenceCount was 4, expected 5`). **Conclusively diagnosed as a
TEST-isolation artifact, NOT a `RecurringInvoiceSpawnService` over-spawn /
double-bill / lost-period bug.** Evidence:

- The unmodified spec is **deterministically GREEN across ≥5 ISOLATED runs**
  (`--tests '*RecurringInvoiceSpawnRestartIT' --rerun-tasks`). Per-tick spawn is
  hard-bounded at `due.stream().sorted().limit(maxCatchup)`; the ledger insert is
  unique-indexed (`tenant_recurring_period_idx`) + ledger-FIRST.
- Every failing full-suite run's ledger dump showed THIS template's
  `RecurringInvoiceOccurrence` rows carrying **distinct** periodKeys mapped 1:1 to
  invoices — **ZERO double-bill, ZERO merged lump, ZERO loss in every run**. The
  only variance was the *count* of distinct periods caught up, and one transient
  row carried a real-wall-clock `spawnedAt` (not this test's fixed clock) —
  proving a **foreign cross-tenant `runDueOnce()` tick from a cached sibling
  Spring context** (the shared singleton Testcontainers Mongo;
  `findAllDueAcrossTenants` is intentionally cross-tenant) advanced THIS
  template's catch-up by another *legitimate, distinct* period. Not a money error
  (the unique index forbids re-billing a period).
- The `occurrenceCount` variant: `RecurringInvoice` is `@Version`-locked and
  `advanceParent` does a reactive read-modify-write of the *denormalized*
  `occurrenceCount`; its exact value under interleaved completion is not a
  guaranteed invariant (no invoice lost — the ledger is exact).

**Money contract intact and proven** (AC-E3 / E-D3: one DRAFT invoice per period,
no double-bill, no merged lump, no loss, bounded per tick). **Fixes are money-safe,
ZERO production-code change:** (a) `RecurringInvoiceSpawnRestartIT` now asserts the
unconditional money invariant on the unique-indexed ledger (every *completed*
spawn bills its period exactly once: distinct periodKeys ↔ distinct invoice ids ↔
existing DRAFT $100 invoices) + progress + idempotency + monotonicity — robust to
a foreign cross-tenant tick; the strict per-tick `≤ max-catchup` bound is covered
by the ≥5 green ISOLATED runs + `RecurringInvoiceSpawnIdempotencyIT` /
`InvoiceNumberGeneratorIT`; (b) a fixed `@Primary Clock` (the documented
`RecurringInvoiceSpawnService` affordance) kills intra-service two-clock-read
drift; (c) `kmosf.recurring-invoice.spawn-job.enabled=false` in the shared
`src/test/resources/application.yml` (the `sla-breach-scheduler` precedent; prod
default unchanged). Two out-of-scope follow-ups were flagged: the
`occurrenceCount` denormalized-counter RMW race (now **RESOLVED** — see
"occurrenceCount atomicity follow-up" below), and deeper test-isolation hardening
for cross-tenant Quartz ticks on the shared Mongo (still open).

**`ServiceAgreementSchedulerIT`** (a PRE-EXISTING Phase-D/home-services
date-flake, **zero Phase-E linkage** — `git diff origin/main..HEAD -- .../module/`
is empty) was fixed the in-repo way: a fixed `@Primary Clock` (mirroring
`ServiceAgreementSchedulerInvalidRruleTest`) + fixed-clock-relative seeds; original
`FREQ=WEEKLY;COUNT=4` intent and exact assertions preserved. NOT a Phase-E
behavior change.

## occurrenceCount atomicity follow-up — RESOLVED (`fix/recurring-occurrence-count-atomic`)

> Branch: `fix/recurring-occurrence-count-atomic` (one PR, stacked on the Phase-E
> branch — Phase E is not yet merged to `main`). Closes the `occurrenceCount`
> out-of-scope follow-up flagged in the E.13 root-cause section above.

**Root cause.** `RecurringInvoiceSpawnService.advanceParent` did a non-atomic
reactive read-modify-write of the `@Version`-locked *denormalized*
`RecurringInvoice.occurrenceCount` (`findByTenantIdAndId` → `setOccurrenceCount(+1)`
+ cursor sets → `save`), and `publishSpawned` did a *second* independent
`findByTenantIdAndId` re-read for the `RECURRING_INVOICE_SPAWNED` payload. Two race
windows ⇒ the operator-visible counter drifted off-by-one under a concurrent spawn
(a foreign cross-tenant tick on the shared Testcontainers Mongo in tests; in
production `POST /recurring-invoices/{id}/spawn-now` racing the scheduled tick — the
job's `@DisallowConcurrentExecution` does not cover `spawn-now`). The authoritative
unique-indexed `RecurringInvoiceOccurrence` ledger was always exactly correct — only
the denormalized counter drifted (never money: zero double-bill, zero loss).

**Fix (production: `RecurringInvoiceSpawnService` only).**
- `advanceParent` is now ONE atomic `ReactiveMongoTemplate.findAndModify` —
  `$inc occurrenceCount` + `$inc version` + `$set lastRunAt / lastSpawnedInvoiceId
  / updatedAt` + `$set nextRunAt` (or `nextRunAt=null` + `status=ENDED`),
  tenant-scoped query, `returnNew(true)`; returns the post-advance document.
  rrule/seedAt/endAt read off the passed-in template (no pre-read);
  `recurringSchedule.next` consumed as `Optional` (NOT `.orElse(null)` inside
  `Mono.fromCallable`, which completes empty on a finite/exhausted RRULE and would
  skip the ENDED advance — incidental hardening). Genuine not-found
  (template deleted mid-tick) ⇒ `switchIfEmpty(Mono.error(3605))` — §9-compliant.
- `publishSpawned` consumes that returned document — the 2nd re-read race window
  is gone.
- `doSpawn` reordered: `maybeAutoFinalize` BEFORE `advanceParent`, so nothing
  failure-prone runs after the atomic `$inc` — the per-`$inc` ↔ per-completed-
  ledger-row pairing is exact and a post-`$inc` compensating-delete drift is
  impossible (strictly better than, and never worse than, the prior code).
- `version` is `$inc`-ed because `findAndModify` bypasses optimistic locking:
  without it a stale concurrent `RecurringInvoiceService` versioned `save` could
  silently revert the money cursor; bumping the numeric `@Version` exactly as
  Spring Data would keeps that conflict loud (fail-fast over silent corruption).

**Now-guaranteed invariant (production).** `advanceParent` is reached exactly once
per successfully-completed `doSpawn` (a duplicate-fire loser short-circuits at the
unique-indexed ledger insert) and nothing failure-prone follows the `$inc`, so in
production `occurrenceCount` == the completed occurrence-ledger row count EXACTLY,
converged regardless of interleaving. Asserting that equality *from a test* is a
two-read cross-document compare (parent doc vs ledger docs), so on the shared
singleton Testcontainers Mongo it is skew-free only against a template a foreign
cross-tenant tick cannot advance (see the test design below) — a test-harness
observation limit, NOT a production caveat.

**Tests.**
- New `RecurringInvoiceOccurrenceCountIT` — the deterministic exact proof. A
  *fresh*, finite `FREQ=DAILY;COUNT=3` template is caught up across bounded ticks
  (`max-catchup=2` ⇒ 2 then 1) to its **terminal `ENDED` state**, then
  `occurrenceCount == completed-ledger-rows == 3` is asserted EXACTLY. Once
  `ENDED`, `findAllDueAcrossTenants` (filters `status:'ACTIVE'`) never re-scans it
  and `COUNT=3` + the unique period index cap the ledger at 3 — the state is
  frozen, so the two-read compare has **zero skew window** and is foreign-tick-
  IMMUNE in isolation AND the full suite. Per-tick it also asserts the
  foreign-tick-immune money invariant.
- `RecurringInvoiceSpawnRestartIT.manyMissedPeriods` **kept** its loose
  foreign-tick-immune `occurrenceCount > 1` sanity check (deliberately NOT
  tightened). A ledger-relative exact form was tried and **failed the full suite**:
  this open-ended DAILY template is perpetually ACTIVE+due, so a foreign
  cross-tenant tick advances it between the parent-doc read and the ledger read
  (the exact artifact this class's Javadoc forbids). Class Javadoc updated:
  `occurrenceCount` RESOLVED in production + a pointer to the exact proof; the
  warning hardened to forbid BOTH hardcoded AND ledger-relative exact cross-read
  `occurrenceCount` totals here.

**Money invariants preserved.** Ledger-insert FIRST, unique
`tenant_recurring_period_idx`, the compensating delete, bounded catch-up
(`limit(maxCatchup)`), the explicit-boolean occurrence probe (never
`switchIfEmpty(doSpawn)`), §9 (`switchIfEmpty` only for genuine not-found) — all
intact. `InvoiceService`, `QuickBooksInvoiceSync`, invoice numbering (E.9–E.11)
untouched. No live Stripe/QBO.

**Flagged residuals (accepted, not fixed here).**
- *R1 (low).* The per-spawn UPDATE `audit_events` row + `@LastModifiedDate`
  callback no longer fire on the cursor advance (`findAndModify` bypasses the
  `Auditable`/`@LastModifiedDate` callback); `updatedAt` is set explicitly so it
  keeps advancing, and the dropped UPDATE audit row is accepted —
  precedent-consistent with `HealthScoreService`'s in-place `updateFirst` (cursor
  advance is denormalized bookkeeping, not a CRM mutation). CREATE auditing via
  `RecurringInvoiceService` is unchanged; `RecurringInvoiceAuditIT` (CREATE-only)
  is unaffected.
- *R4 (low, pre-existing, correct trade).* A stale concurrent
  `RecurringInvoiceService.update/setStatus` versioned `save` during a spawn now
  fails loud with `OptimisticLockingFailureException` (the spawn `$inc`s
  `version`) instead of silently reverting the money cursor — fail-loud over
  corruption.

## Full suite result
- Baseline (main @ 87cb3eb): 399 tests / 0 failures / 0 errors
- After E.6: 422 tests / 0 / 0 / 2 skipped (blocker era — 2 @Disabled AC-E3 specs)
- After E.9–E.13 (this branch): **427 tests / 0 failures / 0 errors / 0 skipped**
  — no main regression (399 unchanged + 28 Phase-E; the 2 formerly-@Disabled AC-E3
  specs enabled & green; InvoiceNumberGeneratorIT 6/0/0; ServiceAgreementSchedulerIT
  3/0/0; RecurringInvoiceSpawnRestartIT 2/0/0). Determinism verified by repeated
  isolated + consecutive full-suite runs.

## OpenAPI spec (docs/api/openapi.json)
- **E.9–E.13 add NO API surface** — the blocker resolution is finalize-time
  numbering + an index migration + test-only changes; no new/changed endpoint or
  schema. `build/openapi/openapi.json` (regenerated by the E.13 full-suite run) is
  **byte-identical** to the committed `docs/api/openapi.json` from E.7. No
  regeneration/commit needed; the HANDOFF artifact is current.
- Baseline (main): 158 paths / 124 schemas
- After E.7: **164 paths / 126 schemas** (+6 paths: /recurring-invoices, /recurring-invoices/{id},
  /recurring-invoices/{id}/status, /recurring-invoices/{id}/spawn-now,
  /invoices/{id}/stripe-checkout, /invoices/{id}/accounting-push; +2 schemas:
  RecurringInvoice + StripeCheckoutService$CheckoutResult). `RecurringInvoice` schema
  present; `Invoice.paymentTerms` property present as the inline `PaymentTerms` enum
  [DUE_ON_RECEIPT,NET_7,NET_15,NET_30,NET_45,NET_60]; `RecurringInvoice.status` enum
  [ACTIVE,PAUSED,ENDED]. `RecurringInvoiceOccurrence`/`StripeWebhookEvent` correctly
  absent (system ledgers, no controller exposes them).

## HANDOFF GATE
N/A — Phase E is BE-only; recurring/Stripe FE deferred to Phase G (E-D13).

## Post-Phase-E follow-up — deeper test-isolation hardening (Quartz autostart) — RESOLVED

Branch `fix/test-isolation-recurring-quartz-cross-talk` (independent PR into `main`, post-Phase-E
merge of #55). Resolves the **second** of the two out-of-scope follow-ups flagged at the end of the
"Catch-up nondeterminism root-cause — RESOLVED (E.13)" section above ("deeper test-isolation
hardening for cross-tenant Quartz ticks on the shared Mongo"). The first follow-up (the
`occurrenceCount` denormalized-counter RMW race) is a separate, orthogonal change.

**Corrected root cause (supersedes the E.13 belief that the test-yml spawn-job disable was
effective).** E.13 added `kmosf.recurring-invoice.spawn-job.enabled=false` to
`src/test/resources/application.yml` ("the sla-breach precedent"). That line is in fact **shadowed**:
Spring Boot loads the profile-agnostic main `src/main/resources/application.properties` at a HIGHER
precedence than the test `src/test/resources/application.yml` (there is no test
`application.properties`). So for every key main `application.properties` defines —
`spring.quartz.auto-startup=true` (:24), `kmosf.recurring-invoice.spawn-job.enabled=...:true` (:37),
`spring.quartz.properties.org.quartz.scheduler.instanceName=KmosQuartzScheduler` (:26) — the test-yml
value never took effect. Only per-IT `@TestPropertySource` (highest precedence) overrode it.
(`sla-breach-scheduler.enabled: false` worked in the yml only because main `application.properties`
does not define that key, so nothing shadowed it — which is why the "precedent" was misleading.)
Consequently the Quartz scheduler **auto-started in every cached `@SpringBootTest` context** and
`RecurringInvoiceJobScheduler` **registered the spawn job** there; that cached, started scheduler then
ticked `runDueOnce()` cross-tenant against the ONE shared Testcontainers Mongo ~60s after boot,
perturbing other tests (the observed `RecurringInvoiceSpawnJob tick failed` WARN). Empirically proven
on this branch via JUnit-XML capture: with only the test-yml form, `RecurringInvoiceSpawnRestartIT`
showed `Scheduler … started` and `QuartzMongoJobStoreIT` showed
`RecurringInvoiceSpawnJob scheduled — first run in 60000ms` despite the disables.

**Fix (test-scope only; ZERO production-code/default change; ZERO money-design change).** A
**profile-specific** `src/test/resources/application-test.properties` (profile-specific reliably
overrides profile-agnostic main `application.properties`) carrying `spring.quartz.auto-startup=false`
(primary — a non-started scheduler stores but never fires triggers, removing the *firing* capability
outright), `kmosf.recurring-invoice.spawn-job.enabled=false` (defense-in-depth, now genuinely
effective), and a unique test scheduler `instanceName` (belt). Activated for the whole test source
set via `spring.profiles.active: test` in `src/test/resources/application.yml` (no `@Profile`
collision — the only main `@Profile` is `DataSeeder @Profile("dev")`; `test` ≠ `dev`, nothing is
`@Profile("!test")`). The sole scheduler-dependent IT, `QuartzMongoJobStoreIT`, opts back in via its
`@TestPropertySource` (`spring.quartz.auto-startup=true` + explicit
`kmosf.recurring-invoice.spawn-job.enabled=false`) so its started scheduler runs ONLY the harmless
one-shot `NoOpQuartzJob` — zero cross-tenant `runDueOnce` even from that one context. Money-design
untouched (ledger-insert-FIRST, unique `tenant_recurring_period_idx`, explicit-boolean probe, bounded
catch-up); recurring durability lives in the `RecurringInvoiceOccurrence` ledger + `nextRunAt` cursor,
never the Quartz JobStore, so a not-started test scheduler is immaterial to correctness.

**Verification.** Validation gate (per-context, JUnit XMLs): `RecurringInvoiceSpawnRestartIT`
(non-opt-in) — scheduler never starts, zero scheduler/spawn/NoOp markers, 2/0/0;
`QuartzMongoJobStoreIT` (opt-in) — `Scheduler KmosQuartzScheduler-test … started`,
`NoOpQuartzJob fired`, **no** `RecurringInvoiceSpawnJob scheduled`, 2/0/0; profile `test` active in
both. Full suite **3/3 consecutive runs green** (`./gradlew --no-daemon -Dorg.gradle.java.home=<JDK17>
test --rerun-tasks`): each EXIT 0 / BUILD SUCCESSFUL / **427 tests, 0 failures, 0 errors, 0 skipped**
(no regression vs the E.13 427 baseline) / **zero `RecurringInvoiceSpawnJob tick` occurrences across
that run's JUnit XMLs** (the authoritative artifact — Gradle does not stream Spring app logs to the
console).

| Sub-phase | Status | SHA | Notes |
|---|---|---|---|
| FU-Q1 — test-isolation: never auto-start Quartz in tests (profile-specific override) | done | (this branch) | application-test.properties (profile `test`) + spring.profiles.active in test yml + QuartzMongoJobStoreIT opt-in; corrected the .properties-over-.yml precedence root cause; 3/3 full-suite green 427/0/0/0, zero spawn-job tick; no prod/money change |
