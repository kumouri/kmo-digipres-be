# PHASE-PROGRESS — "Get Paid" AR / Collections Agent (`get-paid-ar-collections`)

> Fresh ledger for this branch (off `main` @ `3fe15ab`, post-E2-responder merge). Replaces the prior
> E2-responder ledger that occupied this path — that work is already on `main`.
>
> Detail plan: `~/.claude/plans/get-paid-ar-collections.md`. Strategy origin:
> `~/.claude/plans/you-are-an-expert-fluffy-adleman.md` (Tier-1 #1 module). Error band **4600–4619**
> (deliberately past the live AI-demos plan's 4300–4559 allocation + "4560+ reserved"; re-verify the
> `GlobalErrorHandler` frontier at merge time). Module gate `kmosf.modules.ar` (**matchIfMissing=false —
> default OFF**; customer-facing comms).
>
> **⚠️ CLOBBER PROTOCOL (load-bearing).** Built in an ISOLATED worktree
> (`repos/kmo-digipres-be-ar`) off `origin/main` because the live **"AI demos implementation plan"**
> (`fluttering-splashing-dusk.md`) is running **serial-BE** on this repo. AR is NOT in its scope
> (audited 2026-06-09 — clear), but it contends on the four shared files. So: **additive-only** edits to
> `GlobalErrorHandler` (Javadoc), `DomainEventType` (block), `application.properties`, the module
> registry, and `docs/api/openapi.json`; and **DO NOT auto-merge** — hold this as a DRAFT PR, rebase
> onto a moved `main`, and merge only in a coordination window (orchestration idle / halted).
>
> **The acceptance bar:** every reused money/billing core (`Invoice`, `InvoiceService`,
> `StripeCheckoutService`, `RuleActionDispatcher`, `TwilioSmsService`, `AnthropicAiAssistService`) stays
> **empty-diff vs `main`** except the surgical, named seams below; the SENT→OVERDUE sweep + dunning are
> a new default-OFF module, gated so non-AR tenants are byte-identical.

## Sub-phase ledger

| Sub-phase | Scope | Commit | Status |
|---|---|---|---|
| AR-0 | Fresh ledger + detail-plan pointer + branch + draft PR (hedge) | `770ef0d` | DONE |
| AR-1 | `ar` model (`DunningLog`) + repo + `DomainEventType` block (`INVOICE_OVERDUE_*`) + `ArAutoConfiguration` (gate OFF) + `application.properties` doc block + `GlobalErrorHandler` 4600–4619 Javadoc | `4c143e8` | DONE |
| AR-2 | `ArAgingSweepJob` — clone `CoverageNudgeJob`: per-tenant SENT→OVERDUE past `dueAt`+grace (via unchanged `InvoiceService.setStatus`), tiered `INVOICE_OVERDUE_{D3,D7,D14}` emit, explicit-boolean `DunningLog` ledger-insert-FIRST idempotency + `ArAgingSweepIT` | `11cf29b` | DONE |
| AR-3 | Dunning — `DunningRuleSeeder` (idempotent, `OnTheWaySmsAutomation` pattern) + `RuleActionDispatcher` SEND_SMS/SEND_EMAIL wiring + `DunningCopyComposer` (AnthropicAiAssistService on-brand) + one-touch `StripeCheckoutService` link; auto-stop on `INVOICE_PAID` | — | PENDING |
| AR-4 | `PromiseToPay` create/track + AR-aging read API (`ArAgingController`, buckets + totals) | — | PENDING |
| AR-5 | ITs (mirror `CoverageNudge*IT` sweep + a rule-dispatch IT) + `verifyOpenApi` regen + commit `docs/api/openapi.json` | — | PENDING |
| AR-FE | AR-aging dashboard tab + send-nudge/mark-promise actions (separate; only AFTER BE merges) | — | DEFERRED |

## Validation log (local Docker/Testcontainers; orchestrator-run, `--rerun-tasks`)
- **AR-1 + AR-2 (2026-06-09, sub-agent):** `./gradlew compileJava compileTestJava` GREEN (Corretto 21
  launcher, `--no-daemon`; only pre-existing deprecation warnings). New IT
  `./gradlew test --tests "*ArAgingSweepIT" --rerun-tasks` GREEN on Docker 29.4.3 Testcontainers —
  `tests=1 failures=0 errors=0 skipped=0`. Full suite NOT run (CI-minute discipline — orchestrator
  validates). `switchIfEmpty(` grep over `module/ar` = comments only (zero call sites). Touched-file
  set matches the allowed list (verified `git diff origin/main --stat`): new `module/ar/*` +
  `ArAgingSweepIT`; additive-only `DomainEventType` / `GlobalErrorHandler` (Javadoc) /
  `InvoiceRepository` (one finder) / `AutoConfiguration.imports` / `application.properties`.
  `Invoice` / `InvoiceService` / `RuleActionDispatcher` / `StripeCheckoutService` / `TwilioSmsService`
  / `AnthropicAiAssistService` = **empty-diff vs `main`**.

## Key invariants for this branch (carry-forward)
- **`@ConditionalOnProperty(prefix="kmosf.modules.ar", name="enabled", matchIfMissing=false)`** on the job + controller — default OFF; non-AR tenants get no aging sweep, no dunning, byte-identical.
- **Idempotency = explicit-boolean probe + ledger-insert-FIRST** (`DunningLog`, unique `tenant_invoice_tier_idx`, `onErrorResume(DuplicateKeyException → empty)`) — **never `switchIfEmpty(send)`**. The `CoverageNudgeJob` pattern verbatim.
- **Auto-stop:** the dunning ladder subscribes to the existing `INVOICE_PAID` (no new event) and is a no-op once paid.
- **No live external** — Stripe/Anthropic → WireMock; Twilio/email send → `@MockitoBean`/sandbox.
- **Empty-diff** — `Invoice`, `InvoiceService`, `StripeCheckoutService`, `RuleActionDispatcher`, `TwilioSmsService`, `AnthropicAiAssistService` (verify `git diff main`, 0 lines each). Only pre-existing files touched: `GlobalErrorHandler` (Javadoc), `DomainEventType` (additive block), `application.properties` (additive keys), the module registry, `docs/api/openapi.json` (regen).

## Deviations / notes
- **AR-1 row scope corrected:** the original AR-1 ledger row listed `PromiseToPay` + "config props".
  `PromiseToPay` is **AR-4** scope (per the detail plan); AR-1 shipped `DunningLog` only. And the repo's
  default-OFF-module convention (coverage-nudge / gbp-reviews / mole-tripwire) is to carry **no
  `kmosf.modules.<m>.enabled` line** in `application.properties` (absence = OFF under
  `matchIfMissing=false`); so AR-1 added a documenting comment block (env-var opt-in + the `@Value`
  fallback keys) rather than active property lines — matching the precedent exactly, lowest blast radius.
- **SENT→OVERDUE reuses `InvoiceService.setStatus` unchanged (no new repo update method).** `setStatus`
  already permits SENT→OVERDUE cleanly: the invoice is already numbered (so `assignNumberIfIssued` is a
  no-op) and `maybePublishFinalized` only fires on DRAFT→SENT, so OVERDUE emits no `INVOICE_FINALIZED`
  and burns no number. `InvoiceService`'s public surface is therefore **unchanged** (empty-diff), as the
  brief preferred over broadening it.
- **`ArAutoConfiguration` + `ArAgingSweepJob` share ONE gate.** Unlike nurture (default-ON module +
  default-OFF runner on two flags), the AR module is default-OFF wholesale, so both carry the same
  `kmosf.modules.ar` `matchIfMissing=false` gate and flip together — the `ModuleDefinition` only exists
  when the module is on. The `DunningLogRepository` is component-scanned by
  `@EnableReactiveMongoRepositories` (always present, like `CoverageNudgeLogRepository`) — harmless,
  unused while OFF.
- **No `switchIfEmpty(` in `module/ar` executable code** (grep: 4 hits, all Javadoc). Idempotency is the
  explicit-boolean `DunningLog` probe (`.map(e->true).defaultIfEmpty(false)`) + ledger-insert-FIRST +
  `onErrorResume(DuplicateKeyException → empty)`.
