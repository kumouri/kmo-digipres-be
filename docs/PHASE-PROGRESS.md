# PHASE-PROGRESS — T13 Home "Tech Copilot" (BE leg)

Branch: `home-tech-copilot` (off `main` @ `909f8da`)
Plan: `~/.claude/plans/home-tech-copilot.md`
Module: `module/techcopilot/` · gate `kmosf.modules.techcopilot` (default OFF) · error band **4490-4519**

A **RAG-grounded, cited Q&A assistant for field technicians** over a per-tenant corpus of equipment
manuals / SOPs / spec sheets. Deploys the shipped RAG spine (the RE-1 concierge precedent) — the net-new
is the **corpus model + chunking ingest** and the **tech-Q&A surface**. Demo collateral, default-OFF.

## Sub-phase ledger

| # | Sub-phase | Status | Notes |
|---|---|---|---|
| 1 | Detail plan + this ledger | ✅ done | commit 1 |
| 2 | Model + ingest: `EquipmentType`, `TechDoc`+repo, `TechQuery`+repo, `TechDocChunker`(+unit test), `TechDocService`, `RagRetrievalService.retrieveForCorpus` additive overload, `DomainEventType` T13 block | ⬜ pending | |
| 3 | Answer + Q&A: `TechCopilotAnswerService`, `TechCopilotService`, controllers+DTOs, `TechCopilotAutoConfiguration`, `AutoConfiguration.imports`, `GlobalErrorHandler` Javadoc, demo seeder | ⬜ pending | |
| 4 | Tests + docs: 4 new ITs, `CLAUDE.md` T13 entry, app-props doc; run + record regression | ⬜ pending | |
| 5 | Push + ready PR | ⬜ pending | |

## Reuse contract (must stay empty-diff vs `main`)
`AskAiService`, `EmbeddingService`, `OpenAiEmbeddingService`, `EmbeddingPipeline`, `VectorIndex`,
`MongoAtlasVectorIndex`, `AiUsageRecorder`, `ConciergeAnswerService`, `ListingConciergeService`,
`ListingDisclosureService`. **The lone additive seam in a shared core** is
`RagRetrievalService.retrieveForCorpus(...)` (+ the `TECH_DOC_SOURCE_TYPE` constant) — a near-verbatim
clone of the existing `retrieveForListing` minus the `matchesListing` filter, so the RE concierge path is
byte-unchanged (its ITs re-run green as the gate).

## Reactive invariant
`switchIfEmpty` only for genuine not-found (4490/4493). No-context + idempotency seams are explicit
list-empty / boolean branches — **NEVER `switchIfEmpty(create/index/answer)`**.

## Validation gate
`ci.yml` = unit/arch only (no ITs). full-ci OOM-impaired → validate ITs locally via targeted
`./gradlew test --tests` batches; report the clean local IT re-run as the gate.

## Test plan (status filled in sub-phase 4)
- New: `TechDocIngestIT`, `TechCopilotAnswerIT`, `TechCopilotNoContextIT`, `TechCopilotModuleGateIT`, `TechDocChunkerTest` (unit).
- Regression: `module.realestate.*` (esp. `RealEstateConciergeIT`), `service.ai.*` (`RagRetrievalServiceTest`, `EmbeddingPipelineIT`), `OpenApiEndpointIT`.
