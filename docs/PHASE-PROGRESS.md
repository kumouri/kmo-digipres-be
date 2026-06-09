# PHASE-PROGRESS — E4 Gap-Fill Waitlist engine (`waitlist-gapfill-engine`)

> Fresh ledger for this branch (off `main` @ `8d3ad27`, post-E3-review-engine merge). Replaces the prior
> E3-review-engine ledger that occupied this path — that work is already on `main`.
>
> Detail plan: `~/.claude/plans/waitlist-gapfill-engine.md`. Error band **4350–4359**.
> Module gate `kmosf.modules.waitlist` (matchIfMissing=true). The engine is **consumer-triggered +
> dormant by default** — no standalone `@Scheduled` live-SMS job.
>
> **HARD RULE — chairfill byte-equivalent.** Do NOT modify any `module/chairfill/**` file. The engine is a
> parallel generic `…/waitlist/…` package mirroring CF-3's proven pattern.
>
> **Acceptance bar = the regression:** `module/chairfill/.../GapFillWaitlistIT` (9 tests incl. the
> double-YES race, STOP, bad-sig) MUST stay green, unmodified. `InboundSmsService`, `TwilioSmsService`,
> `NoShowRiskScoringService` are empty-diff (0 lines). The double-YES race test in the NEW engine IT proves
> exactly-one-winner.

## Sub-phase ledger

| Sub-phase | Scope | Status | Commit |
|---|---|---|---|
| W0 | Detail plan + fresh ledger | DONE | 0e8137f |
| W1 | model (`WaitlistSlot`/`WaitlistEntry`/`WaitlistOffer`) + repos + `SlotMaterializer` SPI + `NoOpSlotMaterializer` | DONE | 7d09a18 |
| W2 | `WaitlistRankingService` + `GapFillEngine` + `WaitlistOfferExpiryService` | DONE | fc54dc6 |
| W3 | `WaitlistClaimEngine` (atomic findAndModify + materializer dispatch) | DONE | e3e2b82 |
| W4 | `WaitlistAutoConfiguration` + controller + `DomainEventType` block + `GlobalErrorHandler` 4350-4359 + imports | DONE (compileJava green) | a444dc5 |
| W5 | ITs + repo-rename fix + run new + REGRESSION (`GapFillWaitlistIT`) + `OpenApiEndpointIT` | DONE (all green) | (this commit) |
| W6 | regen + commit `docs/api/openapi.json`; final ledger + report | DONE | (this commit) |

## W5 fix — repository simple-name collision (a real bug, caught by the ITs)

Spring Data derives a repository's bean name from the uncapitalized **simple** name. My initial generic
repos `repository.waitlist.WaitlistEntryRepository` / `WaitlistOfferRepository` collided with the shipped
chairfill `module.chairfill.model.WaitlistEntryRepository` / `WaitlistOfferRepository` (same simple names)
→ `BeanDefinitionOverrideException` at context load (would have broken the whole app, not just the test).
Fix: renamed the generic repos to **`WaitlistEngineEntryRepository`** / **`WaitlistEngineOfferRepository`**
(distinct simple names) — chairfill untouched (byte-equivalent preserved). All references updated
(engines, auto-config, controller).

## Key design decisions (mirrors the detail plan)

- **Duplication-vs-shared-util (directive #1):** reimplement the pure ranking rules in
  `WaitlistRankingService`; do NOT extract a shared util (would force a chairfill edit / break
  byte-equivalence). The data source differs (engine = entry-carried stats; chairfill = salon Booking
  history), so it is not literal duplication. ~10-line polarity overlap noted + accepted.
- **`SlotMaterializer` SPI (directive #2):** the E2 `IntentHandler` precedent — consumer registers a
  `@Bean`; engine auto-discovers via `List<SlotMaterializer>` + dispatches to first `supports(slotType)`;
  default `NoOpSlotMaterializer` is the fallback (excluded from the first-pass match). The actual domain
  booking creation is delegated to the consumer (T7 Health RescheduleFlow → PHI-free `Appointment`).
- **Atomic claim (directive #3/#6 — the showpiece):** identical to CF-3 `WaitlistClaimService` —
  `findAndModify` on `waitlist_slot_claims` (`_id = tenantId:slotKey`, `claimedByContactId:null` guard,
  `upsert(true)`, `returnNew(true)`); winner gets the doc, loser hits `DuplicateKeyException` on the
  upsert insert → apology. NEVER `switchIfEmpty(claim)`.
- **No live external (directive #7):** `TwilioSmsService` `@MockitoBean` in ITs; **no AI dependency at
  all** (generic template copy), so no Anthropic/WireMock in the engine.

## Error codes minted
`4350` entry not found (404); `4351` entry invalid (400); `4352` gap-fill slot request invalid (400);
`4353-4359` reserved. Reused: `1130/1132` (module gate), `2530-2532` (Twilio SMS), `1800` (ADMIN guard).

## Domain events
`WAITLIST_ENGINE_OFFER_SENT`, `WAITLIST_ENGINE_SLOT_CLAIMED` (prefixed to NOT collide with chairfill's
`WAITLIST_OFFER_SENT`/`WAITLIST_SLOT_CLAIMED`, which stay byte-equivalent).

## Test results (W5 — all green, local Docker/Testcontainers Mongo)
- `GapFillWaitlistIT` (REGRESSION, chairfill): **11 tests, 0 failures, 0 skipped** — green + unchanged.
- `WaitlistEngineIT` (new engine): **7 tests, 0 failures** — incl. the double-YES race showpiece
  (exactly one CLAIMED + one apology + one materialize).
- `WaitlistRankingServiceTest` (no-Docker): **4 tests, 0 failures**.
- `OpenApiEndpointIT`: **2 tests, 0 failures** — proves the full context boots with the new module.

## Verification (W5/W6 — all pass)
- `git diff main` — **zero `module/chairfill/**` files changed**; `InboundSmsService` /
  `TwilioSmsService` / `NoShowRiskScoringService` are **0 diff lines each** (empty-diff vs `main`).
- reactive-invariant grep: the only `switchIfEmpty` in `service/waitlist/` is in a Javadoc comment; the
  claim is the atomic `mongo.findAndModify` with the `claimedByContactId:null` guard + `upsert(true)` +
  `DuplicateKeyException → loser` (NOT `switchIfEmpty(claim)`). The 3 controller `switchIfEmpty` are
  genuine entity-not-found (4350).
- `docs/api/openapi.json` regenerated — carries `/waitlist/entries`, `/waitlist/entries/{id}`,
  `/waitlist/offers`, `/waitlist/offers/sweep-expired`, `/waitlist/slots/gap-fill`.
