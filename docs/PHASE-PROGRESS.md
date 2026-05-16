# Phase E — Billing, Recurring & Stripe — Progress Ledger

> This file is the crash-recovery source of truth per ultraplan §6 Resilience.
> Each commit is recorded as it lands. The plan §10 stub mirrors this.
> Plan: `back-office-kmo-digipres-phase-E-billing-recurring-stripe.md`

## Branch: `back-office-kmo-digipres-phase-E-billing-recurring-stripe`

| Sub-phase | Status | SHA | Notes |
|---|---|---|---|
| E.1 — error range 3600-3699 + DomainEventType Phase-E block + Invoice.PaymentTerms (additive) | done | 4e3b71f | compileJava clean; full test 399/0/0 — no regression, no error-code collision |
| E.2 — Quartz Mongo JobStore dep + QuartzConfig customizer + properties | done | 15d90d0 | E-D5 RAM-FALLBACK ACTIVE — see decision below; QuartzMongoJobStoreIT 2/0/0 (RAM store, proof job fires) |
| E.3 — RecurringInvoice + RecurringInvoiceOccurrence + repos | done | (this commit) | E-D2 field tables exact; unique tenant_recurring_period_idx; full test 399/0/0 (entities map clean, index auto-creates) |
| E.4 — RecurringInvoiceService + RecurringInvoiceSpawnService + Quartz job | pending | — | — |
| E.5 — StripeWebhookEvent + extended StripeWebhookService + StripeProperties + StripeCheckoutService + controllers | pending | — | — |
| E.6 — BE ITs AC-E1…AC-E8 | pending | — | — |
| E.7 — BE CLAUDE.md in-PR + .claude/* local + docs/api/openapi.json committed | pending | — | — |
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

## Full suite result
- Baseline (main @ 87cb3eb): 399 tests / 0 failures / 0 errors
- After E.6: TBD

## OpenAPI spec (docs/api/openapi.json)
- Baseline (main): 158 paths / 124 schemas
- After E.7: TBD

## HANDOFF GATE
N/A — Phase E is BE-only; recurring/Stripe FE deferred to Phase G (E-D13).
