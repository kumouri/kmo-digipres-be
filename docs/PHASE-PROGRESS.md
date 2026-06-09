# PHASE-PROGRESS — T4 Health "Switchboard AI" (`health-switchboard-ai`)

> Fresh ledger for this branch (off `main` @ `e13cd80`). Replaces the prior `re-midnight-responder`
> (T3) ledger that occupied this path — that work is already on `main`.

Branch `health-switchboard-ai`. Error band **4390–4399**. Module gate: BOTH `kmosf.modules.frontdesk`
AND `kmosf.modules.responder` (default-OFF). Deploys the **E2 inbound responder** to the `frontdesk`
(health) vertical as a logistics-only, PHI-free front-desk overflow + a clinical-message tripwire (no
transcript retained) + deflection analytics. Detail plan: `~/.claude/plans/health-switchboard-ai.md`.

This ledger is the source of truth for resume-after-interrupt: each sub-phase lists its commit + its
validation state. Never trust a missing summary; reconstruct from `git log` + this file.

## Sub-phases

- [x] **T4.0 — detail plan + ledger** (4ecbd85). Plan written, ledger seeded.
- [x] **T4.1 — data + config layer** (f03f6ab). `SwitchboardConfig` (+repo), `SwitchboardDeflectionLog`
      (+category enum +repo), `SwitchboardDeflectionStats`, `SwitchboardIntents` (intent constants +
      default `IntentDefinition`s + the PHI-forbidding health classifier prompt), `SwitchboardRedaction`
      (the fixed marker). PHI-free deflection ledger (no phone/content). Compiles.
- [x] **T4.2 — logistics handlers + tripwire** (c4eabf9). `LogisticsIntentHandler` (7 intents, answered
      from `SwitchboardConfig`), `ClinicalTripwireHandler` (CLINICAL_SYMPTOM → redaction-only Activity +
      staff notify + safe reply, body never persisted), `SwitchboardDeflectionService`. Reuses E2
      `IntentHandler` discovery — no router edit. Reused cores empty-diff verified. Compiles.
- [x] **T4.3 — deflection analytics + controller + handoff recorder** (3035f71).
      `SwitchboardDeflectionRecorder` (`RESPONDER_HANDED_OFF` subscriber, health-scoped),
      `SwitchboardController` (config CRUD + deflection-stats). Compiles.
- [x] **T4.4 — both-modules auto-config** (08703a5). `SwitchboardAutoConfiguration`
      (`@ConditionalOnProperty(frontdesk)` + `@ConditionalOnBean(InboundIntentRouter)`); registered in
      `AutoConfiguration.imports`. Default-OFF; blast-radius zero. Compiles.
- [x] **T4.5 — demo seed** (then refined). `SwitchboardDemoSeeder`
      (`@Profile("demo-health-switchboard")`, idempotent). The 60-second "watch this". Compiles.
- [x] **T4.6 — tests** (682a6d4). `SwitchboardLogisticsIT` (4), `SwitchboardTripwireIT` (2 — the PHI
      fence serialize-and-scan), `SwitchboardModuleGateIT` (3), `SwitchboardDeflectionStatsIT` (2).
      11/11 GREEN. (Refactored `SwitchboardController` to depend on the always-present repos so it loads
      in a frontdesk-on/responder-off context — the gate guard 1132s at runtime.)
- [x] **T4.7 — docs + error codes + openapi.** `GlobalErrorHandler` Javadoc 4390-4399 (`<li>` after
      T3's 4380-4389); `DomainEventType` unchanged (no new event — reuses `RESPONDER_HANDED_OFF`);
      CLAUDE.md T4 entry; openapi NO-DIFF verified (default-OFF → not in spec; 238 paths identical).
      PR opened READY.

## Validation log

- T4.1–T4.5: `./gradlew compileJava` green (see commit messages).
- T4.6: new T4 ITs PASS (counts in the PR body + final report).
- Regression: `module.responder.*`, the frontdesk + `module.frontdesk.nurture.*` ITs,
  `OpenApiEndpointIT` — re-run green (counts in the final report).
- Reused-cores empty-diff vs `main` confirmed (`git diff main --stat` shows no E2 / voicemail core).

## PHI fence proof (release-blocking)

`SwitchboardTripwireIT.symptomInbound_handsOff_persistsNoClinicalText` asserts: a symptom inbound →
safe handoff reply + staff notify + a redaction-only Activity (body = the fixed marker); the persisted
`ConversationState.slots` is empty + `currentIntent` = the category label; a full serialize-and-scan of
`ConversationState` + every `Activity` + the deflection log contains NONE of the forbidden clinical
tokens from the inbound body. The body is never persisted (ConversationState has no body field; the
tripwire handler writes only the marker). Mirrors `HealthFrontDeskVoicemailIT`'s FD-2 forbidden-token
technique.
