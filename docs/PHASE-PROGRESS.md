# PHASE-PROGRESS — AI Proposal / SOW Generator (`sow-proposal-generator`)

> Fresh ledger for this branch (off `main` @ `22a736a`, post-AR-merge).
>
> Detail plan: `~/.claude/plans/sow-proposal-generator.md`. Strategy origin:
> `~/.claude/plans/you-are-an-expert-fluffy-adleman.md` (Tier-1 **#2**). Error band **4620-4639**.
> Module gate `kmosf.modules.proposals` (**matchIfMissing=false — default-OFF**).
>
> **⚠️ CLOBBER PROTOCOL (identical to AR `get-paid-ar-collections`).** Built in an ISOLATED worktree
> (`repos/kmo-digipres-be-sow`) off `origin/main` because the AI-demos orchestration runs **serial-BE**
> on this repo. So: **additive-only** edits to `GlobalErrorHandler` (Javadoc), `DomainEventType`,
> `application.properties`, the imports file, `docs/api/openapi.json`; **DO NOT auto-merge** — hold this
> as a DRAFT PR, rebase onto a settled `main`, and merge only in a coordination window. **Never merge red.**
>
> **Acceptance bar:** the reused cores (`AnthropicAiAssistService`, `Quote`, `LineItem`, `QuotePdfService`,
> `ContractService`) stay **empty-diff vs `main`**; the module is default-OFF (non-proposals tenants
> byte-identical); the 60-sec demo runs (paste discovery notes → a priced SOW PDF drafts → send-to-sign).

## Sub-phase ledger

| Sub-phase | Scope | Commit | Status |
|---|---|---|---|
| SOW-0 | Fresh ledger + detail-plan pointer + branch + draft PR (hedge) | — | IN PROGRESS |
| SOW-1 | `SowDraft` model + repo + `ProposalsAutoConfiguration` (gate OFF) + config props + `DomainEventType` `PROPOSAL_DRAFTED` + `GlobalErrorHandler` 4620-4639 Javadoc | `4c6a583` | **DONE** |
| SOW-2 | `ProposalDraftService` (notes → Sonnet → priced line items + prose → DRAFT `Quote`/SOW; budget-gated; defensive) + `ProposalDraftController` (`POST /proposals/draft` + `GET /proposals/{id}`) + `ProposalDraftIT` | `101c297` | **DONE** |
| SOW-3 | send-to-sign reuse (`ContractService.spawnFromQuote` from a SOW Quote) + SOW PDF (`QuotePdfService`) + thin IT | — | PENDING |
| SOW-4 | ITs green + `verifyOpenApi` (expected no-op — default-OFF) + ledger | — | PENDING |
| SOW-FE | proposal editor UI (paste notes → draft → edit line items/prose → send) — separate, only AFTER BE merges | — | DEFERRED |

## Validation log (local Docker/Testcontainers; orchestrator-run, `--rerun-tasks`)
- **SOW-1 + SOW-2 (2026-06-09, branch base `22a736a`):** `./gradlew compileJava compileTestJava` → **BUILD SUCCESSFUL** (only pre-existing deprecation/unchecked warnings). `./gradlew test --tests "*ProposalDraftIT" --rerun-tasks` → **BUILD SUCCESSFUL**, `ProposalDraftIT` **tests=5 failures=0 errors=0 skipped=0** (happy-path priced DRAFT Quote total 9500.00 + prose + `PROPOSAL_DRAFTED` + GET read-back; garbage-AI → `aiApplied=false` no-throw; budget-exhausted → graceful zero-spend; blank-notes → 4621; module-off tenant → 4620). Full suite NOT run (CI-minute discipline). **Reused-core empty-diff vs merge-base `22a736a` confirmed** (`AnthropicAiAssistService`, `AiUsageRecorder`, `Quote`, `LineItem`, `QuoteService`, `QuotePdfService`, `ContractService` — 0 lines each). `switchIfEmpty(` in new code = only genuine not-found (2200 Quote, 1131 tenant) + the verbatim AI house-key fallback (1203) — **no `switchIfEmpty(create)`**.

## Key invariants for this branch (carry-forward)
- **`@ConditionalOnProperty(prefix="kmosf.modules.proposals", name="enabled", matchIfMissing=false)`** on the service + controller — default-OFF; non-proposals tenants byte-identical.
- **No live external** — Anthropic → WireMock; NO live Documenso send in the build loop (the spawn-to-sign seam is reused but its live send is a go-live human action).
- **Defensive parse never throws** on AI (budget-exhausted / blank / non-JSON → a flagged partial/empty draft). **Never `switchIfEmpty(create)`.**
- **Empty-diff** — `AnthropicAiAssistService`, `Quote`, `LineItem`, `QuotePdfService`, `ContractService` (verify `git diff origin/main`, 0 lines each). Only pre-existing files touched: `GlobalErrorHandler` (Javadoc), `DomainEventType` (additive block), `application.properties`, the imports file, `docs/api/openapi.json` (regen).

## Deviations / notes
- **Prose-storage decision → new `SowDraft` (not reused `Quote` fields).** `Quote` carries only two
  free-text fields (`notes`, `terms`) — insufficient for the four independently-editable SOW sections
  (scope / deliverables / assumptions / timeline) the SOW-FE editor must surface separately. Overloading
  `notes` with a delimited blob would be lossy/un-editable, and adding `Quote` fields would break the
  reused-core empty-diff bar. So the prose lives in an additive `module/proposals/SowDraft` document
  (`TenantScoped`, linked by `quoteId`) — the plan's recommended fallback. `Quote` stays **empty-diff**.
- **Real signatures found + used (no guessing):** `QuoteService.create(Quote)` (UNCHANGED) nulls the id,
  stamps `statusChangedAt`, runs `computeTotals()` (prices the line items), saves a **DRAFT** Quote —
  reused verbatim to materialize the priced SOW; never finalized. `AnthropicAiAssistService`'s public
  methods are domain-specific (`summarizeTimeline`/`draftReply`/`ask`) with a **private** `call(...)`, so
  per the plan + the `VoicemailExtractionService` precedent the draft service is an **additive sibling
  transport** (own `WebClient` + per-tenant-key/house-key resolution + `AiUsageRecorder` gate +
  configurable base-url) — it does **not** call `AnthropicAiAssistService`, which stays empty-diff.
  Default model is Sonnet (`claude-sonnet-4-6`, the `draft-model` convention).
- **IT avoids any live call:** Anthropic → WireMock via `@DynamicPropertySource(kmosf.ai.anthropic.base-url)`
  (the `DunningDispatchIT`/`MoleVisionServiceIT` pattern); `apiKey` is a sandbox fake; the module is opted
  in only inside the IT (`kmosf.modules.proposals.enabled=true` + `Tenant.enabledModules`). No live
  Documenso/Quote-finalize in the loop (send-to-sign is SOW-3). Budget-exhausted case proves **zero**
  WireMock traffic.
- **`4620` surfaced in-band** (vs AR's `4600` which is documented-for-parity but actually returns the
  shared `1132` via `requireEnabled`): the `ProposalDraftController` guard does the per-tenant membership
  check itself and returns `4620`/404 for a non-member tenant, so the gate is directly testable (the IT
  asserts it). The `@ConditionalOnProperty` still makes the routes absent (404) when the deployment flag
  is off — defense in depth. `1131` (tenant-not-found) is reused from the shared registry convention.
- **`@IdempotentRoute` on `POST /proposals/draft`:** a draft is domain-level re-invocable (no find-or-create
  ledger — the mole-classify precedent), but the POST carries a real external effect (Anthropic spend +
  persisted DRAFT Quote), so it requires `Idempotency-Key` (3100 if absent); the IT sends a unique key per
  POST. `GET /proposals/{id}` is not idempotency-gated.
