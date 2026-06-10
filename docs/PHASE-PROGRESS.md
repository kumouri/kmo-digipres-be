# PHASE-PROGRESS — GATE-2 fix: vertical-scope the nurture copy filter (`gate2-nurture-copyfilter-scoping`)

> Fresh ledger for this branch (off `main` @ `6b93cf0`). Replaces the prior `health-rescheduleflow`
> (T7) ledger that occupied this path — that work is already on `main`. Tracks per-sub-phase progress
> + validation so a resume-after-crash reconstructs the frontier from git + this file, never agent
> memory.

Detail plan: `~/.claude/plans/gate2-nurture-copyfilter-scoping.md`.

## The fix (one sentence)

`NurtureMessageComposer` held a single last-wins `@Nullable NurtureCopyFilter`; two consumers (T1
`FairHousingCopyFilter`, T2 `HipaaCopyFilter`) each set it at init, so a process enabling BOTH
realestate+nurture AND frontdesk+nurture had the second clobber the first (worst case: a health
nurture message ships without the HIPAA screen). **Fix:** make the copy filter **vertical-scoped** —
a registry of filters dispatched by the campaign's `vertical`. **No new error codes** (reuses the
`4360`/`4370` advisory markers). **Empty-diff** for all other nurture cores.

## Key investigation results

- **`NurtureCampaign` had NO `vertical` field** (briefing assumed one) — ADDED additively (nullable
  `String`, no `@Builder.Default`; legacy → null).
- Copy-filter verticals: `FairHousingCopyFilter` → `"realestate"`, `HipaaCopyFilter` → `"health"`
  (the briefing's stated values + existing conventions). The new `NurtureCampaign.vertical` is the
  single dispatch source; demo seeders tag their campaigns to match.
- Only `compose(...)` caller = `NurtureRunner` (campaign in scope). Only `setCopyFilter` callers =
  the 2 autoconfigs. No test calls either directly.
- **Safety invariant:** null/legacy campaign vertical → NO vertical-specific filter (today's E1
  behavior); a correctly-tagged vertical always gets its screen, never the wrong one.

## Sub-phases

| # | Sub-phase | Status | Commit | Validation |
|---|-----------|--------|--------|------------|
| G2.1 | Detail plan + this ledger | DONE | b9d55c7 | n/a (docs) |
| G2.2 | SPI (`NurtureCopyFilter.vertical()`/`appliesTo`) + composer registry + `compose(…,vertical)` + `NurtureRunner` thread + `NurtureCampaign.vertical` + filter `vertical()` + autoconfig `registerCopyFilter` + seeder tags | DONE | e756abb | compileJava OK |
| G2.3 | HEADLINE both-verticals IT (`BothVerticalsNurtureCopyFilterIT`) + null-vertical case | DONE | f7a1a6e | 3/3 GREEN |
| G2.4 | Tag the 2 pre-existing headline ITs' campaigns w/ their vertical (new-contract adaptation) + regression (E1 + T1 + T2 nurture + OpenApiEndpointIT) + CLAUDE.md E1 note + ledger finalize | DONE | (this) | full regression GREEN |

## Validation results (G2.4)

- **Headline `BothVerticalsNurtureCopyFilterIT` (3/3 GREEN, Docker/Testcontainers):** both-verticals-no-collision
  (RE→Fair-Housing safe, health→HIPAA safe, neither collides, 2 ledger rows), clean-copy-verbatim
  (no over-substitution), null-vertical-sent-unfiltered (the safe legacy invariant).
- **Regression (all GREEN):**
  - E1 `com.kumouri.kmodigipresbe.nurture.*` (`NurtureRunnerIT`, `NurtureSegmentationIT`,
    `NurtureReplyBookIT`, `NurtureAnalyticsIT`, `NurtureCampaignControllerIT`, + the new
    `BothVerticalsNurtureCopyFilterIT`).
  - T1 `module.realestate.nurture.*` — incl. `RealEstateNurtureFairHousingIT` (headline) +
    `FairHousingCopyFilterTest` (unit) + segmentation/analytics/reply/module-gate.
  - T2 `module.frontdesk.nurture.*` — incl. `FrontDeskNurturePhiSafeIT` (headline) +
    `HipaaCopyFilterTest` (unit) + segmentation/analytics/reply/module-gate/PHI-safe.
  - `OpenApiEndpointIT` GREEN; `docs/api/openapi.json` unchanged vs `main` (the fix adds no endpoint;
    default-OFF modules aren't in the spec — the T1–T7 precedent).
- **Test adaptation (necessary, in-scope):** the 2 pre-existing headline ITs seeded campaigns with NO
  `vertical` (the field didn't exist pre-fix). Under the new vertical-scoped dispatch a null-vertical
  campaign correctly applies NO filter, so they failed (non-compliant copy sent unfiltered). Tagging
  each IT's seeded campaign with its vertical (`realestate`/`health`) — the new contract a real
  deployment + the demo seeders follow — restores the regression's intent (proves the screen fires).
  A faithful new-contract adaptation, NOT a workaround; the safety behavior is independently covered by
  `BothVerticalsNurtureCopyFilterIT.nullVerticalCampaign_...`.
- **Reactive invariants:** no `switchIfEmpty(create/send)` in touched nurture code (only Javadoc that
  *documents* the rule); zero `.block()` in prod nurture. **Error codes:** reused `4360` (Fair-Housing
  advisory) + `4370` (HIPAA advisory); minted NONE.
- **Empty-diff vs `main` (0 lines each):** `NurtureSegmentationService`, `NurtureReplyService`,
  `NurtureAnalyticsService`, `NurtureCampaignController`, the rest of `model/nurture/*`, the nurture
  repos.
- **Concurrent-main check:** branch contained `origin/main` at start; no `git merge origin/main` needed.
