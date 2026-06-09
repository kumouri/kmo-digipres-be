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
| AR-3 | Dunning dispatch — `DunningDispatchService` (dedicated `INVOICE_OVERDUE_{D3,D7,D14}` event-listener, the ChairFill CF-2 `RiskTieredPreventionService` mirror) + `DunningCopyComposer` (budget-gated AnthropicAiAssistService-shape, tier-aware tone, defensive per-tier literal fallback) + one-touch `StripeCheckoutService` `PAYMENT_LINK`; paid-guard auto-stop (reload + status∈{SENT,OVERDUE}); advisory `DUNNING_SENT` + `DunningDispatchIT` | `bfd046e` | DONE |
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
- **AR-3 (2026-06-09, sub-agent):** `./gradlew compileJava compileTestJava` GREEN (Corretto 21 launcher,
  `--no-daemon`; only the pre-existing `PostmarkWebhookService` unchecked + `IdempotencyKey`
  deprecation notes). New IT `./gradlew test --tests "*DunningDispatchIT" --rerun-tasks` GREEN on Docker
  29.4.3 Testcontainers — **`tests=3 failures=0 errors=0 skipped=0`** (send-with-Stripe-link / paid
  auto-stop / no-phone). Full suite NOT run (CI-minute discipline — orchestrator validates).
  `switchIfEmpty(` grep over `module/ar` (AR-3 files) = Javadoc only + the single legitimate
  not-found→house-key-fallback in `DunningCopyComposer.resolveKey` (the `ReminderCopyService` /
  `AnthropicAiAssistService` verbatim pattern — NOT `switchIfEmpty(send/create)`). **AR-3 delta vs the
  AR-2 commit (`70b4611`)** = new `DunningCopyComposer` + `DunningDispatchService` + `DunningDispatchIT`,
  additive `DomainEventType` (one `DUNNING_SENT` constant) + `ArAutoConfiguration` (two `@Bean`s). AR-3
  touched **NO** `GlobalErrorHandler` (no new error code — the defensive-fallback design needed none) and
  **NO** reused core: `Invoice` / `InvoiceService` / `StripeCheckoutService` / `TwilioSmsService` /
  `RuleActionDispatcher` / `AnthropicAiAssistService` / `AiUsageRecorder` = **empty-diff vs `main`**
  (the `GlobalErrorHandler` + `InvoiceRepository` diffs vs `origin/main` are entirely AR-1/AR-2's
  pre-approved additive seams — confirmed absent from the AR-3 `git diff 70b4611` delta).

## Key invariants for this branch (carry-forward)
- **`@ConditionalOnProperty(prefix="kmosf.modules.ar", name="enabled", matchIfMissing=false)`** on the job + controller — default OFF; non-AR tenants get no aging sweep, no dunning, byte-identical.
- **Idempotency = explicit-boolean probe + ledger-insert-FIRST** (`DunningLog`, unique `tenant_invoice_tier_idx`, `onErrorResume(DuplicateKeyException → empty)`) — **never `switchIfEmpty(send)`**. The `CoverageNudgeJob` pattern verbatim.
- **Auto-stop (AR-3, design-corrected):** each tier event is a **one-shot** (the AR-2 `DunningLog` fires each (invoice,tier) at most once) — never a scheduled future send — so the INVOICE_PAID auto-stop is **by construction, no separate `INVOICE_PAID` listener needed**: `DunningDispatchService` reloads the invoice fresh on every event and skips the send if its status is no longer in `{SENT, OVERDUE}` (PAID/VOIDED/PARTIALLY_PAID since the event). The since-paid ladder simply goes quiet.
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
- **No `switchIfEmpty(create/send)` in `module/ar` executable code.** AR-1/AR-2's idempotency is the
  explicit-boolean `DunningLog` probe (`.map(e->true).defaultIfEmpty(false)`) + ledger-insert-FIRST +
  `onErrorResume(DuplicateKeyException → empty)`. AR-3 adds exactly one `switchIfEmpty` call site —
  `DunningCopyComposer.resolveKey`'s not-found→house-key-fallback-or-`1203` — which is the **verbatim**
  `ReminderCopyService` / `AnthropicAiAssistService` key-resolution pattern (a genuine not-found branch,
  NOT a conditional create/send). The `DunningDispatchService` send path has **zero** `switchIfEmpty`.
- **AR-3 uses a dedicated `DunningDispatchService` event-listener, NOT a generic WorkflowRule SEND_SMS**
  (a deliberate, justified design choice — the detail plan's AR-3 row said `DunningRuleSeeder` +
  `RuleActionDispatcher` SEND_SMS wiring; **superseded**). Reason: `RuleActionDispatcher.sendSms`'s body
  comes from `SmsTemplateRegistry.resolve()` as a **literal** string — no AI personalization, no
  per-invoice Stripe pay link, no paid-guard — so the generic dispatcher cannot deliver the product
  (AI-drafted, tier-aware copy + a one-touch pay link + auto-stop). The dunning leg is therefore a
  dedicated subscriber that is **still event-driven** (the strategic requirement) and fully
  controllable, mirroring ChairFill **CF-2**'s `RiskTieredPreventionService` for the exact same reason
  (CF-2 chose a dedicated subscriber over `SEND_SMS` because the generic action has no `REQUIRE_DEPOSIT`
  and no per-contact Claude copy). `RuleActionDispatcher` is **empty-diff** — no rule seeding at all.
- **AR-3 tenant-context for the async handler — CF-2 mirror.** `DunningDispatchService` establishes the
  tenant context exactly as `RiskTieredPreventionService` does: a `@PostConstruct` subscription
  (`events.stream().filter(type).flatMap(handle).subscribeOn(Schedulers.boundedElastic())`) where
  `handle(event)` builds a synthetic `TenantContext(tenantId, null, Set.of("AUTOMATION_AR_DUNNING"))`
  and writes it with `.contextWrite(TenantContextHolder.write(ctx))`. The invoice reload uses the
  **tenant-scoped** `InvoiceRepository.findByTenantIdAndId` (the bare `findById` is NOT auto-scoped).
- **AR-3 Stripe — real `StripeCheckoutService.createCheckoutForInvoice(invoiceId, Mode)` signature; no
  live call.** The shipped method returns `Mono<CheckoutResult>` (`.url()` is the link), needs a live
  `WebClient` against `StripeProperties.apiBaseUrl` + a per-tenant Stripe `IntegrationConnection`. AR-3
  calls it with `Mode.PAYMENT_LINK` for the one-touch link, **best-effort** (`onErrorResume → empty`); if
  no link can be minted (Stripe not connected / upstream fail), the send is skipped (a linkless dunning
  text has no product value). `DunningDispatchIT` avoids any live Stripe by `@MockitoBean
  StripeCheckoutService` returning a fixed `CheckoutResult(payLink, …)` — cleaner than WireMock +
  seeding a Stripe connection, and asserts the mocked link lands in the SMS body + the Claude prompt.
