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
| SOW-1 | `SowDraft` model (only if `Quote` can't hold prose) + repo + `ProposalsAutoConfiguration` (gate OFF) + config props + `DomainEventType` `PROPOSAL_DRAFTED` + `GlobalErrorHandler` 4620-4639 Javadoc | — | PENDING |
| SOW-2 | `ProposalDraftService` (notes → Sonnet → priced line items + prose → DRAFT `Quote`/SOW; budget-gated; defensive) + `ProposalDraftController` (`POST /proposals/draft` + `GET /proposals/{id}`) + `ProposalDraftIT` | — | PENDING |
| SOW-3 | send-to-sign reuse (`ContractService.spawnFromQuote` from a SOW Quote) + SOW PDF (`QuotePdfService`) + thin IT | — | PENDING |
| SOW-4 | ITs green + `verifyOpenApi` (expected no-op — default-OFF) + ledger | — | PENDING |
| SOW-FE | proposal editor UI (paste notes → draft → edit line items/prose → send) — separate, only AFTER BE merges | — | DEFERRED |

## Validation log (local Docker/Testcontainers; orchestrator-run, `--rerun-tasks`)
- (pending — SOW-1 onward)

## Key invariants for this branch (carry-forward)
- **`@ConditionalOnProperty(prefix="kmosf.modules.proposals", name="enabled", matchIfMissing=false)`** on the service + controller — default-OFF; non-proposals tenants byte-identical.
- **No live external** — Anthropic → WireMock; NO live Documenso send in the build loop (the spawn-to-sign seam is reused but its live send is a go-live human action).
- **Defensive parse never throws** on AI (budget-exhausted / blank / non-JSON → a flagged partial/empty draft). **Never `switchIfEmpty(create)`.**
- **Empty-diff** — `AnthropicAiAssistService`, `Quote`, `LineItem`, `QuotePdfService`, `ContractService` (verify `git diff origin/main`, 0 lines each). Only pre-existing files touched: `GlobalErrorHandler` (Javadoc), `DomainEventType` (additive block), `application.properties`, the imports file, `docs/api/openapi.json` (regen).

## Deviations / notes
- (none yet)
