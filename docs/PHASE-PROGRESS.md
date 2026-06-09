# PHASE-PROGRESS — T2 "Health RevenueRevive" (`health-revenuerevive`)

> Fresh ledger for this branch (off `main` @ `5de940f`, the just-merged T1 "RE Database Goldmine").
> Replaces the prior `re-database-goldmine` (T1) ledger that occupied this path — that work is already on
> `main`.
>
> **What:** Deploy the shipped E1 Nurture/Cadence engine to the **`frontdesk` (health) vertical, PHI-free** —
> the structural twin of T1. Dormant patients are auto-segmented on **logistics only** (recency + optional
> value band; NEVER a diagnosis/clinical feature) → multi-touch **PHI-free** SMS/email nurture cadences with
> backoff → positive-reply auto-rebooks via the booking-link SMS path → per-segment reactivation analytics.
> Health deployment + **HIPAA copy guardrail** + reply→rebook wiring + analytics surface + demo seed; NOT an
> engine reimplementation. Module `module/frontdesk/nurture/`; gates BOTH `kmosf.modules.frontdesk` AND
> `kmosf.modules.nurture`; error band **4370-4379**. Detail plan: `~/.claude/plans/health-revenuerevive.md`.

## The headline correctness property
**PHI-free by construction on ALL nurture outbound.** Every composed message (template AND AI-personalized)
is screened by `HipaaCopyFilter` — a `NurtureCopyFilter` impl that REUSES the shipped FD-4 `HipaaReplyLint`
(`module/frontdesk/reviews/HipaaReplyLint.lint(body)`). A flagged draft (patient-status confirmation or
clinical vocabulary) is **replaced with a vetted safe generic template** (never sent PHI-ish, never dropped;
logged advisory code 4370). This is T2's analogue of T1's Fair-Housing screen, and the release-blocking
guarantee. Wired only for a frontdesk+nurture deployment via a side-effect bean calling
`NurtureMessageComposer.setCopyFilter` — null ⇒ byte-identical E1.

## Empty-diff decision — ZERO E1 edits
T1 already added the lone E1 change: the `@Nullable NurtureCopyFilter` SPI + `setCopyFilter` on
`NurtureMessageComposer` (null-safe; the `conciergeRouter`/`intentRouter` setter precedent). **T2 needs no
further E1 change** — it only contributes a frontdesk copy-filter `@Bean` + wires it. If an E1 change were
found necessary, STOP and flag (per the directive). It was NOT necessary.

**E1 nurture cores + frontdesk cores stay empty-diff vs `main`.** T2 only ADDS:
`module/frontdesk/nurture/{HipaaCopyFilter, FrontDeskNurtureService, FrontDeskNurtureAutoConfiguration,
FrontDeskNurtureReplyHandler, FrontDeskNurtureDemoSeeder}` + `controller/frontdesk/FrontDeskNurtureController`
+ the `GlobalErrorHandler` Javadoc `4370-4379` `<li>` + `CLAUDE.md` + (regenerated) `docs/api/openapi.json`.

## Mirror map (T1 realestate → T2 frontdesk)
| T1 | T2 |
|---|---|
| `RealEstateNurtureService` | `FrontDeskNurtureService` |
| `FairHousingCopyFilter` (reuses `FairHousingLint`) | `HipaaCopyFilter` (reuses `HipaaReplyLint`) |
| `RealEstateNurtureAutoConfiguration` | `FrontDeskNurtureAutoConfiguration` |
| `RealEstateNurtureReplyHandler` (vertical=realestate) | `FrontDeskNurtureReplyHandler` (vertical=frontdesk) |
| `RealEstateNurtureDemoSeeder` (`demo-realestate`) | `FrontDeskNurtureDemoSeeder` (`demo-frontdesk`) |
| `RealEstateNurtureController` | `FrontDeskNurtureController` |

---

## Sub-phase ledger

| Sub-phase | Status | Commit | Notes |
|---|---|---|---|
| T2.0 — detail plan + this ledger | DONE | `e71272a` | plan `~/.claude/plans/health-revenuerevive.md` |
| T2.1 — `HipaaCopyFilter` + `HipaaCopyFilterTest` | DONE | `d997176` | reuses `HipaaReplyLint`; unit 7/7 green (no-Docker) |
| T2.2 — service + reply handler + autoconfig + controller + `GlobalErrorHandler` 4370-4379 | DONE | _(this commit)_ | wires the 4 beans incl. `setCopyFilter` side-effect; both-module gate; autoconfig registered in `AutoConfiguration.imports`; compiles clean |
| T2.3 — `FrontDeskNurtureDemoSeeder` | DONE | _(this commit)_ | `@Profile("demo-frontdesk")`; "Bright Smiles Dental" + 12 lapsed patients (logistics-only: backdated `Activity` + PHI-free `Appointment`; 2 opted-out; A-tier WON deals) + health campaign; compiles clean |
| T2.4 — T2 ITs | DONE | _(this commit)_ | PHI-safe headline (4/4) + logistics-only segmentation (3/3, incl. no-clinical-field reflection assert) + reply-rebook (3/3) + analytics (3/3) — all green on Testcontainers; + the T2.1 unit (7/7) = 20 T2 tests, 0 failures |
| T2.5 — `CLAUDE.md` + `openapi.json` | PENDING | | T2 entry; regenerate spec (expect no diff — default-OFF) |
| Validation — new + regression ITs | PENDING | | E1-nurture (`nurture.*`) + a frontdesk IT + `OpenApiEndpointIT`; capture counts |

## Validation status (frontier)
- Not yet validated — implementation begins at T2.1.

## Error codes minted
- **4370** — PHI-free safe-fallback substitution (advisory, logged WARN; not a thrown HTTP error — mirrors T1's 4360).
- **4371-4379** — RESERVED for health-nurture growth.
- Reused (NOT re-allocated): 4301/4302/4303 (campaign not-found/inactive/invalid), 4310 (reply-no-enrollment, swallowed), 1130/1132 (module gate), 1800 (ADMIN).

## Invariants
- ZERO E1 edits (T1's SPI sufficed); E1 nurture cores + frontdesk cores empty-diff vs `main`.
- `switchIfEmpty` only for genuine not-found; no `switchIfEmpty(create/send)` (the seeder's first-run create is a genuine not-found, allowed).
- Reactive, no `.block()` on the Netty loop.
- No live external — Twilio/Email `@MockitoBean`, Anthropic→WireMock, `NurtureRunner` default-OFF; no host/key/charge/send in the loop.
- Per-tenant config never hardcoded (booking link from `IntegrationConnection`; safe templates `@Value`-resolved).
