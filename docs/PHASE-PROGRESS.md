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
| G2.1 | Detail plan + this ledger | DONE | (this) | n/a (docs) |
| G2.2 | SPI (`NurtureCopyFilter.vertical()`/`appliesTo`) + composer registry + `compose(…,vertical)` + `NurtureRunner` thread + `NurtureCampaign.vertical` + filter `vertical()` + autoconfig `registerCopyFilter` + seeder tags | PENDING | — | — |
| G2.3 | HEADLINE both-verticals IT (`BothVerticalsNurtureCopyFilterIT`) + null-vertical case | PENDING | — | — |
| G2.4 | Regression (E1 + T1 + T2 nurture + OpenApiEndpointIT) + CLAUDE.md E1 note + ledger finalize | PENDING | — | — |

## Validation results

_(filled at G2.4)_
