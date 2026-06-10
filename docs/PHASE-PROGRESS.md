# PHASE-PROGRESS — T10 Real Estate "Listing Prep Studio" (BE leg)

Branch: `realestate-listing-prep` (off `main` @ 954504a)
Plan: `~/.claude/plans/realestate-listing-prep.md`
Module: `module/realestate/listingprep/` · gate `kmosf.modules.realestate` · error band 4460-4469

## What RE-4 already covers vs T10 net-new

RE-4 Marketing Studio already ships: photo intake, `AiVisionService` feature/condition extraction, MLS
description + social captions + email blast, `FairHousingLint`, draft→approve. The listing already feeds the
concierge (RAG). **T10 net-new = (1) a 4-week dated social CALENDAR, (2) the cohesive "Listing Prep Studio"
prep-pack tying description + calendar + email to a Listing.** Everything else is reused; RE-4 cores stay
empty-diff.

## Sub-phase ledger (ALL DONE)

- [x] **P0 — detail plan** (`~/.claude/plans/realestate-listing-prep.md`) + fresh `docs/PHASE-PROGRESS.md`. Commit `7ecfee6`.
- [x] **P1 — model** `ListingPrepPack` + `ListingPrepPackRepository` (+ nested `SocialPost`/`PhotoNote`/`FairHousingFlag`).
- [x] **P2 — `SocialCalendarGenerationService`** (Anthropic Sonnet, JSON-array of week+dayOffset+channel+copy; best-effort→empty).
- [x] **P3 — `ListingPrepService`** orchestrator (reuse `AiVisionService.extract` + `MarketingGenerationService` + `FairHousingLint`; calendar date-assign + safe-substitute held posts; DRAFTED; approve/skip). Commit `da53d51`.
- [x] **P4 — `ListingPrepController`** (6 endpoints, gated + STAFF) + `ListingPrepDemoSeeder` (`@Profile("demo-realestate-listingprep")`).
- [x] **P5 — wiring** `RealEstateAutoConfiguration` (2 beans + @Value) + `DomainEventType` (`LISTING_PREP_GENERATED`/`_APPROVED`) + `GlobalErrorHandler` 4460-4469 Javadoc.
- [x] **P6 — T10 ITs** (`RealEstateListingPrepIT` 6 + `RealEstateListingPrepModuleGateIT` 2). Commit `5c60e82` (also fixed the concurrent AI-spend race).
- [x] **P7 — regression** (`module.realestate.*` incl. RE-4 marketing + FairHousing, `*AiVisionServiceIT`, `OpenApiEndpointIT`) green.
- [x] **P8 — docs** (CLAUDE.md T10 entry) + PR.

## Validation log

- `./gradlew compileJava compileTestJava` — PASS (clean; only pre-existing unrelated unchecked-ops notes).
- `./gradlew test --tests "…RealEstateListingPrepIT"` — PASS (6/6).
- `./gradlew test --tests "…RealEstateListingPrepModuleGateIT*"` — PASS (2/2: OFF→beans absent, ON→present).
- `./gradlew test --tests "module.realestate.*" --tests "*AiVisionServiceIT" --tests "…OpenApiEndpointIT"` —
  PASS (**76 tests, 0 failures, 0 errors**; RE-1..RE-5 + RE-4 marketing + nurture/FairHousing + responder + T10).
- `docs/api/openapi.json` — **unchanged vs `main`** (T10 endpoints are module-gated OFF → absent from the spec;
  the FE hand-writes the `api/*.ts`, the T1-T9 precedent).

## Reactive-invariant check

`grep switchIfEmpty module/realestate/listingprep` → all uses are genuine not-found (4253/4460
`switchIfEmpty(Mono.error)`), the house-key fallback (RE-4 `resolveKey` precedent), or the idempotent
demo-seeder `switchIfEmpty(seedFresh)`. **Zero `switchIfEmpty(create)`**; approve/skip use explicit-boolean
status checks (4461). AI/PDF off the Netty loop (shared `AiVisionService` + WebClient async). The two AI
generations run **sequentially** (not `Mono.zip`) so the per-tenant `AiUsageRecorder.record` write is serialized.

## Empty-diff confirmation (RE-4 + AiVision cores) — VERIFIED

`git diff main --stat` shows ZERO change to `ListingMarketingService.java`, `MarketingGenerationService.java`,
`FairHousingLint.java`, `AiVisionService.java`, `Listing.java`, `ListingPhoto.java`,
`ListingMarketingDraft.java`. T10 calls them; never edits them. The `realestate/listingprep/` package is
strictly additive; the only edits to shared files are additive (`RealEstateAutoConfiguration` +2 beans,
`DomainEventType` +2 constants, `GlobalErrorHandler` +Javadoc). Full diff: 13 files, +2048/-64 (the -64 is the
fresh T9→T10 PHASE-PROGRESS rewrite).
