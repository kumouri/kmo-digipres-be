# Phase 3 (NMM AI intake) — Coverage-Window Automation — Progress Ledger

> Crash-recovery source of truth. Each sub-phase is its own commit; this row is set
> →in-progress (committed) as the first action and →done+results (committed) as the
> last action of every sub-phase. Plan: `i-had-an-idea-ticklish-rivest.md` §2 (Feature B /
> B2 tripwire), §4, §5 Phase 3, §6, §8. Builds on the just-merged Phase 1 (voicemail-to-lead)
> + Phase 2 (mole-photo triage) spine — the tripwire REUSES the Phase-2 pipeline
> (`MoleVisionService` classify + `FileStorageService` store + notify-Rob) and the UNCHANGED
> `MilestoneService.create` to drop a re-treatment Milestone on the customer's Project.

## Branch: nmm-ai-intake-phase-3-coverage-window  (base main @ d4361b8 — the merge that includes Phases 1+2)
## Model: Opus implementer = Opus 4.8 (1M context). BE-only — no FE this phase. No live external services (§7 — WireMock/mock/sandbox throughout).

| Sub-phase | Status | SHA | Build (compileJava+compileTestJava) | Mandated checks | Deviations |
|---|---|---|---|---|---|
| 3.1 error range 4013-4029 (carved from Phase-2's reserved 4013-4039) + DomainEventType Phase-3 block + mole-tripwire token type/service (HMAC, mirrors PublicWidgetTokenService but carries projectId) + admin token-issuer endpoint + additive nullable Project.coverageWindowEndsAt | done | (this commit) | compileJava BUILD SUCCESSFUL exit 0 | GlobalErrorHandler Javadoc: carved 4013-4029 = Phase 3 from Phase-2's reserved 4013-4039 (4030-4039 still reserved); 4013 tripwire widgetType mismatch (mirrors 4010/2700), 4014 no image part, 4015 unsupported media type, 4016 token's Project not found (defensive); 1600-1603 (tripwire-token rejections) + 1200-1203 (AI) + 1310/1311 (storage) + 2530-2532 (Twilio SMS) + 1300 (Activity) + 3410/3411 (UNCHANGED MilestoneService) + 1800 (RoleGuard) reused not re-allocated. DomainEventType +3 advisory constants (MOLE_TRIPWIRE_REPORTED / RETREATMENT_MILESTONE_CREATED / COVERAGE_NUDGE_SENT). MoleTripwireToken record {tenantId,widgetType,projectId,expiresAt} + MoleTripwireTokenService = NEW additive sibling of PublicWidgetTokenService (same base64url HMAC-SHA256 scheme + same kmosf.security.widget-token-secret; the ONE difference = a 4-field payload tenantId|widgetType|projectId|expiresAt carrying the Project id the 3-field Phase-2 token cannot hold — so PublicWidgetTokenService stays empty-diff). MoleTripwireTokenController (admin): POST /integrations/mole-tripwire/tokens/{projectId}, RoleGuard ADMIN + tenant-scoped Project load (4016 if absent) → issue token; @ConditionalOnProperty(kmosf.modules.mole-tripwire, matchIfMissing=true). Project.coverageWindowEndsAt = additive nullable Instant (the coverage-nudge selector; null on legacy/non-coverage Projects, no migration/index change; Project core otherwise untouched). | none |
| 3.2 mole-tripwire public report endpoint (MoleTripwireController, exchange.getMultipartData) + the MoleTripwireService pipeline (REUSE Phase-2: store Attachment → MoleVisionService classify → threshold; above-threshold mole → load Project from token → re-treatment Milestone via UNCHANGED MilestoneService.create + Activity + notify Rob + advisory events) | pending | — | — | — | — |
| 3.3 coverage-window check-in nudge job (CoverageNudgeJob @Scheduled, default-OFF, idempotent per (project,period) via explicit-boolean CoverageNudgeLog ledger probe + ledger-insert-FIRST) | pending | — | — | — | — |
| 3.4 BE ITs (MoleTripwireIT report + Milestone; CoverageNudgeIT idempotent per period) + MoleTripwireItStorageTestConfig | pending | — | — | — | — |
| 3.5 docs in-PR (CLAUDE.md Phase-3 section + openapi.json regen) + final ITs GREEN + mark PR ready | pending | — | — | — | — |

## No-live-external boundary (§7) — recorded
- [ ] Anthropic base URL configurable and pointed at WireMock in every test; api key sandbox fake; widget-token-secret a test secret; NO host hardcoded; NO live charge/send/upload anywhere; nudge job default-OFF (no live sends in CI / any default run). — to be re-confirmed at 3.4/3.5.

## §9 reactive invariant — recorded
- [ ] switchIfEmpty over the new moletripwire package + the nudge job = genuine not-found / key-resolution-fallback only; ZERO switchIfEmpty(create/process). The Contact find-or-create seam is EXPLICIT-BOOLEAN; the tripwire has NO idempotency ledger (a per-customer report is intentionally re-invocable, the Phase-2 precedent — so no switchIfEmpty(process) on the tripwire); the nudge idempotency seam is an EXPLICIT-BOOLEAN CoverageNudgeLog probe + ledger-insert-FIRST, NEVER switchIfEmpty(create). Blocking I/O off the Netty loop (S3 putBytes = non-blocking S3AsyncClient; base64 inside the reused MoleVisionService = boundedElastic). — to be re-confirmed at 3.4/3.5.
- [ ] Reused cores empty-diff vs the Phases-1+2-inclusive base (d4361b8 — the branch base; verify against d4361b8, NOT local `main` which is behind — the Phase-2 lesson): MoleVisionService / MoleTriageService / ProjectService / MilestoneService / AnthropicAiAssistService / TwilioSmsService / EmailService / ActivityCrudService / FileStorageService / IntegrationConnection(+Service+Repository) / PublicWidgetTokenService + all Phase-1/2 packages. Strictly-additive: the new integration/moletripwire/* package, MoleTripwireController + MoleTripwireTokenController, the CoverageNudgeJob + CoverageNudgeLog ledger + repo, the additive nullable Project.coverageWindowEndsAt field, the DomainEventType Phase-3 block, the GlobalErrorHandler 4013-4029 Javadoc, config props. — to be re-confirmed at 3.5.

## Baseline note (IMPORTANT for the validator)
The branch is based on `d4361b8` (PR #67 merge — Phases-1+2-inclusive). Reused-cores empty-diff
MUST be verified against `d4361b8` (the correct base that includes Phases 1+2), NOT local `main`
(which may be behind that merge — a `git diff main` would falsely show Phase-1/2 files as new,
the Phase-2 lesson).

## Opus implementer note
Phase 3 implemented by Opus 4.8 (1M context) as a single supervised pass; Docker is up so the
ITs are run to GREEN locally before the PR is marked ready (the non-negotiable gate). Crash-hedge:
push + open a DRAFT PR as soon as 3.1 compiles, `git push` after EVERY sub-phase commit, mark the
PR ready only at the end. Each sub-phase: explicit `git add <paths>` (never -A/.; never the root
CLAUDE.md or `.claude/*`), compileJava+compileTestJava after each, full ITs to GREEN at the end.
