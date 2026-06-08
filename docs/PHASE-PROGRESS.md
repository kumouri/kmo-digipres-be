# RE-1 — Real Estate Concierge: module + disclosure indexing + grounded concierge — Progress Ledger

> Crash-recovery source of truth for `realestate-concierge-phase-1-grounded-concierge`
> (off `main` @ `e7befa0`). Fresh ledger for this change (supersedes the prior HS-1 ledger that
> occupied this path — that work is already on `main`).
>
> Spec: `~/.claude/plans/real-estate-concierge-flagship.md` §3 (the disclosure-indexing finding),
> §4 (key decisions), §6 (RE-1 detailed spec), §6.4 (retrieval scoping), §6.5 (strict grounding),
> §6.6 (inbound routing seam). Error band: **4250-4259**.

## Goal (RE-1)

A buyer texts a listing's tracked Twilio number a disclosure question → gets a **disclosure-grounded,
cited answer** in one turn, or a graceful **`HANDOFF`** when the disclosures don't cover it —
**never a hallucination** — with the conversation state persisted (for RE-2/RE-3). ChairFill's
inbound YES/STOP path stays **byte-identical** when `smsMode` is unset.

## Design as built

- **Disclosure-text indexing (§3 crux).** `ListingDisclosureService` (module-owned `@Bean`) embeds the
  disclosure's **text** directly and upserts it via `EmbeddingService` + `VectorIndex.upsert` as source
  type **`"ListingDisclosure"`** with **`listingId` metadata** (+ contentPreview/title/disclosureType).
  The core `EmbeddingPipeline` is **untouched** (blast-radius zero). Best-effort: a blank text or an
  embedding failure logs 4252 and leaves `indexedAt` null (the row is never lost); on success it stamps
  `indexedAt` and emits `LISTING_DISCLOSURE_INDEXED`.
- **Listing-scoped retrieval (§6.4).** `RagRetrievalService.retrieveForListing(tenant, q, listingId, topK)`
  — an additive overload (the contact/deal `retrieve` is byte-unchanged → NMM unaffected). Two guards:
  `sourceType == "ListingDisclosure"` (no CRM-vector bleed) + `matchesListing` on `metadata.listingId`
  (no cross-listing bleed). `topK` default 12 (the in-memory filter narrows to one listing).
- **Strict no-hallucination grounding (§6.5).** `ConciergeAnswerService` (sibling of `OfferCopyService`)
  — a hard-refusal system prompt (the `MultiTradeExtractionStrategy` "do not invent" discipline applied to
  an answer): answer ONLY from the disclosure context, else reply EXACTLY `HANDOFF`. `ListingConciergeService`
  orchestrates: retrieve → **no-chunks short-circuit to HANDOFF (never calls the model with empty context)**
  → strict Claude → `HANDOFF`-token detection → cited answer. Any AI failure (1200/1202/1203) or a blank
  answer also degrades to HANDOFF (never a fabricated/empty answer).
- **Citations.** Carried from the RAG chunk (sourceId/contentPreview/score); the router resolves the
  human `disclosureType` from the persisted `ListingDisclosure` and stamps a `TurnCitation` on the
  assistant turn (the FE proof-of-grounding viewer in RE-5 reads this).
- **Conversation state.** `ConciergeConversation` + embedded `ConciergeTurn` (with per-answer citations);
  states `ASKING`/`HANDED_OFF` reached in RE-1, the rest declared for RE-2/RE-3.
- **smsMode inbound seam (§6.6).** `InboundSmsService` reads `config.smsMode` from the verified Twilio
  connection. STOP wins first (TCPA, unchanged). `smsMode=="realestate"` + a wired `ConciergeInboundRouter`
  → delegate the non-STOP body to the concierge; absent/`chairfill` (or no router) → the **byte-identical**
  ChairFill YES/STOP path. The router is wired onto the existing `InboundSmsService` bean via a setter
  (`RealEstateAutoConfiguration` invokes it). For a pure-realestate deployment (chairfill off) RE provides
  the `InboundSmsService` via `@ConditionalOnMissingBean` (a 2-arg constructor, null claimService — the YES
  path is unreachable in realestate mode). The `TwilioInboundSmsController` gate became
  `@Conditional(InboundSmsModuleEnabledCondition)` (chairfill OR realestate) — byte-equivalent for
  chairfill-only.
- **Module gate.** `RealEstateAutoConfiguration` (`@ConditionalOnProperty(kmosf.modules.realestate.enabled)`,
  default off) + `Tenant.enabledModules`; controllers `@ConditionalOnProperty`-gated (absent from OpenAPI
  when off). Rides the core CRM/RAG/embedding spine directly (no salon-spa/home-services dep).

## Files

### New — `module/realestate/`
- `RealEstateAutoConfiguration.java` (the gate + hand-built beans + the inbound-seam wiring bean)
- `model/`: `Listing` + `ListingRepository`, `ListingDisclosure` + `ListingDisclosureRepository`,
  `ConciergeConversation` + `ConciergeConversationRepository`, `ConciergeTurn` (+ embedded `TurnCitation`),
  `DisclosureType`, `ConversationState`
- `service/`: `ListingService`, `ListingDisclosureService` (owns embed+upsert+`LISTING_DISCLOSURE_INDEXED`)
- `concierge/`: `ConciergeAnswerService` (strict Anthropic caller), `ListingConciergeService`
  (strict-grounded answerer), `ConciergeInboundRouter` (the RE-side multi-turn router the seam delegates to)
- `controller/`: `ListingController`, `ListingDisclosureController` (authenticated STAFF + module-gated)

### New — core (small, additive)
- `integration/twilio/InboundSmsModuleEnabledCondition.java` (chairfill-OR-realestate controller gate)

### Edited (additive, byte-equivalent when realestate off / smsMode unset)
- `service/ai/rag/RagRetrievalService.java` (+`retrieveForListing` overload + `matchesListing` + the
  `LISTING_DISCLOSURE_SOURCE_TYPE` constant)
- `integration/twilio/InboundSmsService.java` (+`smsMode` seam + optional `ConciergeInboundRouter` setter +
  a 2-arg RE constructor + nullable claimService; STOP-first + ChairFill path unchanged)
- `controller/integration/TwilioInboundSmsController.java` (gate → `@Conditional(InboundSmsModuleEnabledCondition)`)
- `automation/DomainEventType.java` (+`LISTING_DISCLOSURE_INDEXED`, `CONCIERGE_INBOUND_RECEIVED`)
- `controller/advice/GlobalErrorHandler.java` (+4250-4259 doc band)
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (+RealEstateAutoConfiguration)

### Tests
- `src/test/java/.../module/realestate/RealEstateConciergeIT.java` — the crux IT (deterministic RAG via an
  in-memory `@Primary` `VectorIndex`; Anthropic→WireMock; Twilio→`@MockitoBean`). Covers: (d) indexing
  stamps `listingId` metadata; (a) grounded cited answer; (b1) no-chunk → HANDOFF, model never called;
  (b2) model HANDOFF token → handoff; (c) cross-listing isolation; inbound grounded SMS; bad sig → 4000.

## Validation status

- `./gradlew compileJava compileTestJava` — GREEN.
- IT run + regression gates — see the final report (per-class counts).

## Hard gates

1. ChairFill inbound YES/STOP byte-identical when `smsMode` unset — `GapFillWaitlistIT` re-run as a gate.
2. No hallucination: out-of-context / no-chunks → `HANDOFF`, never a fabricated answer — proven by the IT
   (no-chunk short-circuit asserts the model was NEVER called; HANDOFF-token case asserts no answer sent).
3. Module-gated, blast-radius zero; existing behavior + NMM byte-equivalent.
4. Error band 4250-4259; reuse 1200-1203 (AI), embedding/RAG codes, 4000-4003 (Twilio sig), 1800 (RoleGuard).
