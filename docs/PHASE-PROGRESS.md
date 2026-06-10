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

## Sub-phase ledger

- [ ] **P0 — detail plan** (`~/.claude/plans/realestate-listing-prep.md`) + fresh `docs/PHASE-PROGRESS.md`. Commit 1.
- [ ] **P1 — model** `ListingPrepPack` + `ListingPrepPackRepository` (+ nested `SocialPost`/`PhotoNote`/`FairHousingFlag`).
- [ ] **P2 — `SocialCalendarGenerationService`** (Anthropic Sonnet, JSON-array of week+dayOffset+channel+copy; best-effort→empty).
- [ ] **P3 — `ListingPrepService`** orchestrator (reuse `AiVisionService.extract` + `MarketingGenerationService` + `FairHousingLint`; calendar date-assign + safe-substitute held posts; DRAFTED; approve/skip).
- [ ] **P4 — `ListingPrepController`** (6 endpoints, gated + STAFF) + `ListingPrepDemoSeeder` (`@Profile`).
- [ ] **P5 — wiring** `RealEstateAutoConfiguration` (2 beans + @Value) + `DomainEventType` (2 events) + `GlobalErrorHandler` 4460-4469 Javadoc.
- [ ] **P6 — T10 ITs** (`RealEstateListingPrepIT`) + a no-Docker pure test where applicable.
- [ ] **P7 — regression** (`module.realestate.*` incl. RE-4 marketing + FairHousing, `*AiVisionServiceIT`, `OpenApiEndpointIT`) green.
- [ ] **P8 — docs** (CLAUDE.md T10 entry) + PR.

## Validation log

(filled as sub-phases land)

## Empty-diff confirmation (RE-4 + AiVision cores)

To verify at the end: `git diff main --stat` shows ZERO change to `ListingMarketingService.java`,
`MarketingGenerationService.java`, `FairHousingLint.java`, `AiVisionService.java`, `Listing.java`,
`ListingPhoto.java`, `ListingMarketingDraft.java`. T10 calls them; never edits them. The `realestate/listingprep/`
package is strictly additive; the only edits to shared files are additive (`RealEstateAutoConfiguration` +2
beans, `DomainEventType` +2 constants, `GlobalErrorHandler` +Javadoc).
