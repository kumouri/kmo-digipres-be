# Phase D — Time & Expenses — Progress Ledger

> This file is the crash-recovery source of truth per ultraplan §6 Resilience.
> Each commit is recorded as it lands. The §10 stub mirrors this.

## Branch: `back-office-kmo-digipres-phase-D-time-and-expenses`

| Sub-phase | Status | SHA | Notes |
|---|---|---|---|
| D.1 — error range 3500-3599 + DomainEventType Phase-D constants | done | 7c5316c | GlobalErrorHandler Javadoc + 8 new DomainEventType constants |
| D.2 — TimeEntry/Expense entities + repositories | done | 8e78694 | (tenantId,userId,startedAt desc) leading index; findFirst…EndedAtIsNull |
| D.3 — TimeSplitService (local-day N-segment split) + TimeEntryService | done | 3acec89 | explicit-boolean idempotency; switchIfEmpty only for 3500 not-found |
| D.4 — ExpenseService (CRUD/approve/reject/invoice-from-expenses) | done | 26a460c | switchIfEmpty only for 3511 not-found and project-header fallback |
| D.5 — Controllers + @IdempotentRoute + RoleGuard + @ConditionalOnProperty | done | adac90e | 3 @IdempotentRoute endpoints; ADMIN guard on delete/approve/reject |
| D.6 — BE ITs (AC-D1…AC-D8; incl. TimerMidnightSplitIT) | done | 7bdabfa | 399 tests / 0 failures / 0 errors |
| D.7 — BE CLAUDE.md in-PR + .claude/* local + docs/api/openapi.json committed | done | (this commit) | 158 paths / 124 schemas |

## Full suite result (after D.6)
- **399 tests / 0 failures / 0 errors** — no main regression

## OpenAPI spec (docs/api/openapi.json)
- Paths: 158 (was 143 on main — +15 new time/expense paths)
- Schemas: 124 (was 120 on main — +4 new schemas)
- TimeEntry schema: confirmed present
- Expense schema: confirmed present

## HANDOFF GATE
FE work MUST NOT start until the BE PR is merged and docs/api/openapi.json is committed on BE main.
**[ ] Set SATISFIED after merge**
