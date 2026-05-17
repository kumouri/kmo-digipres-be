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
| E.7 — BE CLAUDE.md in-PR + .claude/* local + docs/api/openapi.json committed | done | (this commit) | CLAUDE.md Phase E SHIPPED note + blocker; .claude/* local (gitignored); openapi.json 164 paths/126 schemas |
| E.8 — Final BE green + PR | pending | — | — |

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

## ESCALATED BLOCKER (AC-E2 multi / AC-E3 full catch-up) — needs Opus-validator/user decision

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

Status quo shipped: AC-E1, AC-E4, AC-E5, AC-E6, AC-E7, AC-E8 fully green;
AC-E2 single-period spawn green; AC-E2 multi-period + AC-E3 full one-tick catch-up
are `@Disabled` executable specs in `RecurringInvoiceSpawnRestartIT` (enable once
resolved); `currentBehavior_blockedByTenantNumberIdx_butMoneyInvariantsHold`
asserts the money invariants that DO hold.

## Full suite result
- Baseline (main @ 87cb3eb): 399 tests / 0 failures / 0 errors
- After E.6: **422 tests / 0 failures / 0 errors / 2 skipped** — no main regression
  (399 unchanged + 23 new Phase-E; 2 skipped = the @Disabled AC-E3 full-catch-up specs)

## OpenAPI spec (docs/api/openapi.json) — committed E.7
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
