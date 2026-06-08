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

---

# RE-2 — multi-turn qualification + `Deal` + `LeadScoringV2Service` tiering + hot-handoff — Progress Ledger

> Crash-recovery source of truth for `realestate-concierge-phase-2-qualification` (off `main` @ `cceff2c`,
> RE-1 merged). Spec: `~/.claude/plans/real-estate-concierge-flagship.md` RE-2 (§5) + decision 3. Error
> band: **4260-4262** (advisory only). RE-2 adds **zero net-new ML** and does **NOT** modify the scorer.

## Goal (RE-2)

Across the buyer's SMS conversation, Claude extracts **budget / timeline / financing (+ buy/sell intent)**
into a **`Deal`**; the **UNCHANGED nightly `LeadScoringV2Service`** tiers that Deal/Contact HOT/WARM/COLD;
a **`LEAD_SCORE_UPDATED` subscriber** does the **hot-handoff** (alert the agent on a HOT buyer). The
grounded Q&A (RE-1) and qualification **coexist** — a buyer question still gets a cited answer.

## Design as built

- **Multi-turn qualification.** `QualificationExtractionService` (hand-built `@Bean`, the
  `VoicemailExtractionService.extractRaw` transport shape — per-tenant Anthropic key + house-key, budget
  gate, WireMock base-url; reuses 1200/1202/1203) runs a **strict-JSON** extraction over the
  conversation-so-far → `{budget, timeline, financing, preApproved, intent}`. Defensive/best-effort: blank
  conv → empty result no spend; any AI/parse failure → empty result (never throws). The router runs it
  **after** the grounded answer/handoff turn is persisted, **gated by a cheap `hasQualificationSignal`
  pre-filter** (money/financing/timeline/buy-sell vocabulary or a money-shaped number) so a pure factual
  question ("how old is the roof?") never triggers a paid extraction call → **RE-1 model-call behavior is
  byte-identical** (the `RealEstateConciergeIT` call-count gate) and cost is saved.
- **Accumulate + materialize.** `QualificationService.merge` accumulates fields onto the
  `ConciergeConversation.qualification` (a later turn never clears an earlier non-null). On **enough signal
  (a budget)** it find-or-creates the buyer **`Contact`** (by phone — the `TwilioVoicemailService`
  precedent, existing contact reused untouched) and find-or-creates the per-`(buyer × listing)` **`Deal`**
  (keyed via the conversation `dealId` → idempotent re-entry updates the same Deal): stage `NEW`,
  `value`=budget, `primaryContactId`=buyer, `customFields` = `{source:"concierge", listingId, timeline,
  financing, preApproved, intent}`. Links `contactId`/`dealId` onto the conversation, advances state
  `ASKING`→`QUALIFYING`, emits `CONCIERGE_LEAD_QUALIFIED`. Best-effort (4261 → keep the qualification,
  never drop the conversation).
- **Scoring (reuse, untouched).** The materialized Deal flows through the **byte-equivalent** nightly
  `LeadScoringV2Service`, which tiers the Contact off its Deals and emits the existing `LEAD_SCORE_UPDATED`
  — no duplication, no scorer change.
- **Hot-handoff.** `LeadHandoffService` — a `@PostConstruct` subscriber on `LEAD_SCORE_UPDATED` (the CF-2
  `RiskTieredPreventionService` / `RebookingNudgeService` pattern + synthetic `TenantContext`). For a
  **HOT** tier whose Contact has a **concierge-sourced realestate Deal** (`isConciergeSourced`), it
  **ledger-inserts-FIRST** a `HotHandoffLog` (unique `(tenant, deal)` → a re-fired event does zero
  duplicate work), best-effort alerts the agent (SMS + email to the per-tenant
  `IntegrationConnection(twilio).config.notifyPhone/notifyEmail` — the `notifyRob` precedent, gated by
  `kmosf.realestate.handoff-notify`), and emits `CONCIERGE_HOT_HANDOFF`. Module-gated + re-checks
  `Tenant.enabledModules` → a hard **no-op for non-realestate / non-HOT / non-concierge** events.

## Files

### New — module
- `concierge/QualificationExtractionService.java` (strict-JSON Claude extractor, extractRaw shape)
- `concierge/QualificationService.java` (merge + Contact/Deal materialization; the concierge/listing
  customField markers + `isConciergeSourced`/`conciergeListingId` recognition helpers)
- `concierge/LeadHandoffService.java` (`LEAD_SCORE_UPDATED` `@PostConstruct` subscriber; ledger-first +
  best-effort agent alert)
- `model/BuyerQualification.java` (embedded on the conversation)
- `model/HotHandoffLog.java` + `model/HotHandoffLogRepository.java` (idempotency ledger, unique (tenant,deal))

### Edited (additive; RE-1 + ChairFill + scorer byte-equivalent)
- `concierge/ConciergeInboundRouter.java` (+the post-answer qualification step + the `hasQualificationSignal`
  pre-filter; the RE-1 answer/handoff path + citations unchanged)
- `model/ConciergeConversation.java` (+the `qualification` field — RE-1 declared the state enum; this adds
  the embedded value)
- `RealEstateAutoConfiguration.java` (+`QualificationExtractionService`, `QualificationService`,
  `LeadHandoffService` beans; router bean takes the two qualification services)
- `automation/DomainEventType.java` (+`CONCIERGE_LEAD_QUALIFIED`, `CONCIERGE_HOT_HANDOFF`)
- `controller/advice/GlobalErrorHandler.java` (+4260-4262 doc band)

### Tests
- `src/test/java/.../module/realestate/RealEstateQualificationIT.java` — (1) a buyer text revealing
  budget/timeline materializes a concierge Deal (value/customFields asserted) + advances to QUALIFYING +
  the grounded Q&A still cites the disclosure (coexistence; two Anthropic calls differentiated by request
  body in WireMock); (2) hot-handoff fires the agent SMS + writes the ledger on a HOT concierge
  `LEAD_SCORE_UPDATED`, idempotent on re-fire; (3) no-op for a non-concierge Deal and for a WARM tier;
  (4) best-effort — a Claude extraction 500 never drops the conversation or creates a Deal, the grounded
  answer still goes through.

## Validation status

- `./gradlew compileJava compileTestJava` — GREEN.
- `./gradlew cleanTest test --tests "*RealEstateQualificationIT" --tests "*RealEstateConciergeIT"
  --tests "*LeadScoringV2IT" --tests "*GapFillWaitlistIT" --tests "*NoShowRiskScoringIT"
  --tests "*OpenApiEndpointIT"` — **GREEN**. Per-class: RealEstateQualificationIT 5/0/0;
  RealEstateConciergeIT 5/0/0; LeadScoringV2IT 4/0/0; GapFillWaitlistIT 11/0/0; NoShowRiskScoringIT 8/0/0;
  OpenApiEndpointIT 2/0/0 (tests/failures/errors).

## Hard gates

1. `LeadScoringV2Service` + `LeadScore` **byte-equivalent** (zero diff vs `main`) — `LeadScoringV2IT` green.
2. RE-1 grounding + ChairFill inbound **byte-equivalent** — `RealEstateConciergeIT` + `GapFillWaitlistIT`
   green (the `hasQualificationSignal` pre-filter keeps RE-1's factual questions from adding a model call).
3. Best-effort extraction/handoff — a Claude/SMS failure never drops the conversation or corrupts the Deal
   (proven by the best-effort IT + the ledger-first idempotency).
4. Module-gated, blast-radius zero. Error band 4260-4262 (advisory); reuse 1200-1203 (AI), 2530-2532 (SMS),
   the Deal codes (1400/1401).
