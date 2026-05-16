# Phase E — Billing, Recurring & Stripe — Progress Ledger

> This file is the crash-recovery source of truth per ultraplan §6 Resilience.
> Each commit is recorded as it lands. The plan §10 stub mirrors this.
> Plan: `back-office-kmo-digipres-phase-E-billing-recurring-stripe.md`

## Branch: `back-office-kmo-digipres-phase-E-billing-recurring-stripe`

| Sub-phase | Status | SHA | Notes |
|---|---|---|---|
| E.1 — error range 3600-3699 + DomainEventType Phase-E block + Invoice.PaymentTerms (additive) | done | (this commit) | compileJava clean; full test 399/0/0 — no regression, no error-code collision |
| E.2 — Quartz Mongo JobStore dep + QuartzConfig customizer + properties | pending | — | — |
| E.3 — RecurringInvoice + RecurringInvoiceOccurrence + repos | pending | — | — |
| E.4 — RecurringInvoiceService + RecurringInvoiceSpawnService + Quartz job | pending | — | — |
| E.5 — StripeWebhookEvent + extended StripeWebhookService + StripeProperties + StripeCheckoutService + controllers | pending | — | — |
| E.6 — BE ITs AC-E1…AC-E8 | pending | — | — |
| E.7 — BE CLAUDE.md in-PR + .claude/* local + docs/api/openapi.json committed | pending | — | — |
| E.8 — Final BE green + PR | pending | — | — |

## Quartz-store resolution decision (record before E.4)
- [ ] `io.fluidsonic.mirror:quartz-mongodb:2.2.0-rc2` resolved + context boots with Mongo store → **Mongo store active**
- [ ] OR dependency failed → **E-D5 RAM-fallback active** (durability via the Mongo occurrence ledger + `nextRunAt` cursor)

## Full suite result
- Baseline (main @ 87cb3eb): 399 tests / 0 failures / 0 errors
- After E.6: TBD

## OpenAPI spec (docs/api/openapi.json)
- Baseline (main): 158 paths / 124 schemas
- After E.7: TBD

## HANDOFF GATE
N/A — Phase E is BE-only; recurring/Stripe FE deferred to Phase G (E-D13).
