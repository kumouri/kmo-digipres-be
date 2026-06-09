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
| SOW-0 | Fresh ledger + detail-plan pointer + branch + draft PR (hedge) | `f0210bd` | DONE |
| SOW-1 | `SowDraft` model + repo + `ProposalsAutoConfiguration` (gate OFF) + config props + `DomainEventType` `PROPOSAL_DRAFTED` + `GlobalErrorHandler` 4620-4639 Javadoc | `4c6a583` | **DONE** |
| SOW-2 | `ProposalDraftService` (notes → Sonnet → priced line items + prose → DRAFT `Quote`/SOW; budget-gated; defensive) + `ProposalDraftController` (`POST /proposals/draft` + `GET /proposals/{id}`) + `ProposalDraftIT` | `101c297` | **DONE** |
| SOW-3 | `SowPdfService` (line items + totals + prose) + `GET /proposals/{id}/pdf` + send-to-sign reuse (`ContractService.spawnFromQuote`) + `SowPdfIT` | `b5d3de3` | **DONE** |
| SOW-4 | **Orchestrator full-module validation** (clean `--rerun-tasks`): `ProposalDraftIT` 5 + `SowPdfIT` 3 **+ boot guard `OpenApiEndpointIT` 2**, all 0-fail/0-error. `verifyOpenApi` = **convention-correct NO-OP** (`openapi.json` untouched — default-OFF `/proposals` routes aren't in the default-context spec, the AR lesson). | `<this commit>` | **DONE** |
| SOW-FE | proposal editor UI (paste notes → draft → edit line items/prose → download SOW PDF → send-to-sign) — separate FE worktree, chairfill/AR-FE mirror, hand-written `api/proposals.ts` | — | PENDING (building) |

## BE module status: COMPLETE (SOW-0…SOW-4). Remaining = SOW-FE (proposal editor UI) + the coordinated additive-rebase merge onto a settled `main` (hold-merge until BE+FE done + green — **never merge red**). Owner directive: build through FE complete, then coordinated merge.

## Validation log (local Docker/Testcontainers; orchestrator-run, `--rerun-tasks`)
- **SOW-3 (2026-06-09, branch base `22a736a`):** `./gradlew compileJava compileTestJava` → **BUILD SUCCESSFUL** (only pre-existing Gradle-9 deprecation warnings; no new javac warnings). `./gradlew test --tests "*ProposalDraftIT" --tests "*SowPdfIT" --rerun-tasks` → **BUILD SUCCESSFUL**: `SowPdfIT` **tests=3 failures=0 errors=0 skipped=0** (PDF render → non-empty `application/pdf`, `%PDF-` magic, >1 KB, extracted text contains a line-item value `4000` + total `9500.00` + every prose-section marker Scope/Deliverables/Assumptions/Timeline + their content; send-to-sign reuse → a SOW DRAFT Quote flipped to ACCEPTED flows through the UNCHANGED `ContractService.spawnFromQuote` to a SOW `Contract` carrying the `quoteId`, `variables` snapshot has `lineItems`, **zero** `DocumensoClient` interactions; module-off tenant → `4620`/404 on `/pdf`); `ProposalDraftIT` **tests=5 failures=0 errors=0 skipped=0** (re-verified — the additive controller endpoint did not regress SOW-2). Full suite NOT run (CI-minute discipline). **Reused-core empty-diff vs fork point `22a736a` re-confirmed** (`QuotePdfService`, `QuoteService`, `ContractService`, `ContractPdfService`, `Quote`, `LineItem`, `AnthropicAiAssistService`, `DocumensoClient` — 0 lines each, committed AND working-tree). `switchIfEmpty(` in SOW-3 code = only genuine not-found (2200 Quote ×2) + the optional-prose `Mono.defer(render(quote,null))` fallback (renders quote-only, **does not create**) — **no `switchIfEmpty(create)`**.
- **SOW-1 + SOW-2 (2026-06-09, branch base `22a736a`):** `./gradlew compileJava compileTestJava` → **BUILD SUCCESSFUL** (only pre-existing deprecation/unchecked warnings). `./gradlew test --tests "*ProposalDraftIT" --rerun-tasks` → **BUILD SUCCESSFUL**, `ProposalDraftIT` **tests=5 failures=0 errors=0 skipped=0** (happy-path priced DRAFT Quote total 9500.00 + prose + `PROPOSAL_DRAFTED` + GET read-back; garbage-AI → `aiApplied=false` no-throw; budget-exhausted → graceful zero-spend; blank-notes → 4621; module-off tenant → 4620). Full suite NOT run (CI-minute discipline). **Reused-core empty-diff vs merge-base `22a736a` confirmed** (`AnthropicAiAssistService`, `AiUsageRecorder`, `Quote`, `LineItem`, `QuoteService`, `QuotePdfService`, `ContractService` — 0 lines each). `switchIfEmpty(` in new code = only genuine not-found (2200 Quote, 1131 tenant) + the verbatim AI house-key fallback (1203) — **no `switchIfEmpty(create)`**.

## Key invariants for this branch (carry-forward)
- **`@ConditionalOnProperty(prefix="kmosf.modules.proposals", name="enabled", matchIfMissing=false)`** on the service + controller — default-OFF; non-proposals tenants byte-identical.
- **No live external** — Anthropic → WireMock; NO live Documenso send in the build loop (the spawn-to-sign seam is reused but its live send is a go-live human action).
- **Defensive parse never throws** on AI (budget-exhausted / blank / non-JSON → a flagged partial/empty draft). **Never `switchIfEmpty(create)`.**
- **Empty-diff** — `AnthropicAiAssistService`, `Quote`, `LineItem`, `QuotePdfService`, `ContractService` (verify `git diff origin/main`, 0 lines each). Only pre-existing files touched: `GlobalErrorHandler` (Javadoc), `DomainEventType` (additive block), `application.properties`, the imports file, `docs/api/openapi.json` (regen).

## Deviations / notes
- **SOW-3 SOW PDF renderer → new `SowPdfService`, a sibling of `QuotePdfService` (NOT a modification).**
  `QuotePdfService` uses **OpenPDF 2.0.3** (the iText fork, `com.lowagie.text.*`; `build.gradle`
  `com.github.librepdf:openpdf:2.0.3`) and wraps its blocking iText render on
  `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`. `SowPdfService` **mirrors that idiom
  exactly** — same library, same Helvetica font ladder (H1/H2/BODY/BODY_BOLD), the same line-items +
  totals `PdfPTable` construction copied verbatim, the same `boundedElastic` blocking bridge, and the same
  `DigiPresBeException(..., 2100, 500)` PDF-failure code — but as a NEW class in `module/proposals/` that
  ALSO appends the four `SowDraft` prose sections (Scope / Deliverables / Assumptions / Timeline) under
  the quote totals and titles the doc "STATEMENT OF WORK". `QuotePdfService` stays **empty-diff**.
  `SowPdfService` is `@Component` (an always-present stateless renderer with no routes/side-effects — the
  same posture as the always-present `QuotePdfService` and `SowDraftRepository`; harmless when the module
  is off). Endpoint `GET /proposals/{id}/pdf` (module-gated + STAFF) loads the tenant-scoped Quote
  (`findByTenantIdAndId`, miss → `2200`/404 per the `get` posture) + its optional `SowDraft`, renders, and
  returns `application/pdf` bytes.
- **SOW-3 send-to-sign decision → REUSED AS-IS; the SOW PDF is NOT wired as the contract document.**
  Read the real signatures: `ContractService.spawnFromQuote(quoteId, templateId)` requires the Quote to be
  **ACCEPTED** (→3704) + an active `ContractTemplate` (→3705), then builds a DRAFT `Contract` snapshotting
  the quote's line items into `variables`. Critically, the contract's signable **document is rendered by
  `ContractPdfService` from the template's jmustache `bodyTemplate` string** at `/send` time — there is NO
  seam to inject a pre-rendered PDF (the SOW PDF) as the contract document without modifying
  `ContractService` / `ContractPdfService` / the Documenso core, all of which MUST stay empty-diff. Per the
  SOW-3 brief's explicit branch ("if it would require modifying those cores, DON'T — reuse as-is"), the
  decision is **reuse-as-is**: send-to-sign is the existing `POST /contracts/quotes/{quoteId}/spawn-contract`
  → `spawnFromQuote` → Documenso path with **zero new send code**. The **signable Contract carries the
  priced Quote** (line items snapshotted into `variables`); the **SOW prose lives in the `SowDraft` and is
  surfaced via the new SOW PDF** (`GET /proposals/{id}/pdf`) for review / download. The IT proves a
  SOW-drafted DRAFT Quote, flipped to ACCEPTED, flows through the UNCHANGED `spawnFromQuote` to a SOW
  `Contract` carrying the `quoteId`, with `DocumensoClient` `@MockitoBean`'d → **no live send**.
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
