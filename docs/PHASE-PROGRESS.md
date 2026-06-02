# Phase 2 (NMM AI intake) — "Is This a Mole?" Photo-Triage Pipeline — Progress Ledger

> Crash-recovery source of truth. Each sub-phase is its own commit; this row is set
> →in-progress (committed) as the first action and →done+results (committed) as the
> last action of every sub-phase. Plan: `i-had-an-idea-ticklish-rivest.md` §2 (Feature B),
> §4, §5 Phase 2, §6, §8. Builds on the just-merged Phase 1 (voicemail-to-lead) spine —
> the photo channel is a thin front end on the same shared intake/notify core, with a NEW
> additive `MoleVisionService` mirroring the Phase-1 `VoicemailExtractionService` /
> `AnthropicAiAssistService` exactly (but for vision).

## Branch: nmm-ai-intake-phase-2-photo-triage  (base main @ 9e8b2f4 — the merge that includes Phase 1)
## Model: Opus implementer = Opus 4.8 (1M context). BE-only — no FE this phase. No live external services (§7 — WireMock/mock/sandbox throughout).

| Sub-phase | Status | SHA | Build (compileJava+compileTestJava) | Mandated checks | Deviations |
|---|---|---|---|---|---|
| 2.1 error range 4010-4039 + DomainEventType Phase-2 block + MoleClassification value types + MoleVisionService core (vision, mirrors AnthropicAiAssistService) | done | <PENDING> | compileJava BUILD SUCCESSFUL exit 0 | error-code block 4010-4039 added to GlobalErrorHandler Javadoc only (no handler code touched, no existing `<li>` modified): 4010 widget-type mismatch (mirrors 2700), 4011 no image part, 4012 unsupported media type; 1600-1603 (PublicWidgetTokenService token rejections) + 1200-1203 (AI) + 1310/1311 (storage) + 2530-2532 (Twilio SMS) + 1300 (Activity) reused not re-allocated. DomainEventType +2 advisory constants (MOLE_PHOTO_CLASSIFIED/MOLE_LEAD_CREATED) before private ctor. MoleClassificationCategory enum {MOLE,VOLE,GOPHER,NONE,UNSURE} + fromWire (case-insensitive, null/unrecognized → UNSURE, never throws — the adapter boundary). MoleClassification record {category,confidence,rationale} + unsure()/isPest()/toSummaryLine(). MoleVisionService = NEW additive sibling of AnthropicAiAssistService: same resolveKey (per-tenant IntegrationConnection(anthropic).secrets.apiKey → kmosf.ai.anthropic.house-key fallback → 1203/412), same AiUsageRecorder.checkBudget() BEFORE + record() AFTER, same configurable base-url kmosf.ai.anthropic.base-url (default real, → WireMock in tests), same x-api-key/anthropic-version POST + 1202/502 on non-2xx; the ONE difference = an image (base64) content block + a text block in the user message. Strict system prompt → ONLY JSON {classification,confidence,rationale}; defensive parse (extractJsonObject strips fences/prose; blank/parse-fail/unrecognized → MoleClassification.unsure(); clamps confidence to [0,1]; never throws). Base64 encode on Schedulers.boundedElastic() (§9/Safety — CPU-bound off the Netty loop). | none |
| 2.2 MolePhotoIntakeParams + MoleTriageController (public multipart endpoint, exchange.getMultipartData) + MoleTriageService skeleton (token verify → store Attachment → classify → threshold) | <PENDING> | <PENDING> | <PENDING> | <PENDING> | <PENDING> |
| 2.3 MoleTriageService lead path: find-or-create Contact (explicit-boolean) + Activity(NOTE) + best-effort notify Rob + advisory events + return classification | <PENDING> | <PENDING> | <PENDING> | <PENDING> | <PENDING> |
| 2.4 BE ITs (MoleVisionService WireMock unit/IT; intake IT: multipart → Attachment+Contact+Activity+notify+events+returned classification; auth-failure; below-threshold/unsure) | <PENDING> | <PENDING> | <PENDING> | <PENDING> | <PENDING> |
| 2.5 docs in-PR (CLAUDE.md Phase-2 section + openapi.json regen) + final ITs GREEN + PR | <PENDING> | <PENDING> | <PENDING> | <PENDING> | <PENDING> |

## No-live-external boundary (§7) — recorded
- [ ] Anthropic base URL configurable and pointed at WireMock in every test; api key sandbox fake; widget-token-secret a test secret; NO host hardcoded; NO live charge/send/upload anywhere. — (filled at 2.4/2.5)

## §9 reactive invariant — recorded
- [ ] switchIfEmpty over the new molevision package = genuine not-found / key-resolution-fallback only; ZERO switchIfEmpty(create/process) on the find-or-create seam. — (filled at 2.3/2.4)
- [ ] Reused cores empty/additive diff vs main: AnthropicAiAssistService / AiUsageRecorder / TwilioSmsService / EmailService / ContactCrudService / ActivityCrudService / FileStorageService / S3FileStorageService / IntegrationConnection*. — (verified at 2.5)

## Opus implementer note
Phase 2 implemented by Opus 4.8 (1M context) as a single supervised pass; Docker is up so the
ITs are run to GREEN locally before the PR (the non-negotiable gate — Phase 1's implementer
reported "complete" on a compile-only build whose ITs actually failed 6/6; this phase does not
repeat that). Each sub-phase: explicit `git add <paths>` (never -A/.; never the root CLAUDE.md
or `.claude/*`), compileJava+compileTestJava after each, full ITs to GREEN at the end.
