# HS-1 — Multi-trade voicemail → DRAFT WorkOrder — Progress Ledger

> Crash-recovery source of truth for `home-services-front-desk-phase-1-multitrade-voicemail`
> (off `main` @ `c003c3a`). Each sub-step is its own commit. Fresh ledger for this change
> (supersedes the prior GBP-token ledger that occupied this path — that work is already on `main`).
>
> Spec: `~/.claude/plans/home-services-front-desk.md` §2 (architecture), §4 (HS-1 detail),
> §4.8 (ITs), §4.9 (file list).

## Goal

A home-services tenant's signed Twilio transcription callback produces, in addition to the
existing Contact + `Activity(CALL,INBOUND)` + notify, a **DRAFT `WorkOrder`** carrying the
AI-extracted `trade`/`urgency`/`symptom`/`jobValueBand`. **NMM's mole behavior is
byte-equivalent; `TwilioVoicemailIT` passes UNCHANGED.**

## Design (mirrors the `AiVisionService`→`MoleVisionService` strategy precedent)

- `VoicemailExtractionService` becomes the **shared Anthropic text transport** — takes
  `model` + `systemPrompt` as params (no model `@Value`), returns the raw parsed `JsonNode`
  (the `AiVisionService.extract` open-schema shape). Key resolution + budget gate
  (`checkBudget`/`record`) + defensive `extractJsonObject` + codes `1200-1203` live here once.
  The user content prefix `"Voicemail transcript:\n\n"` is identical for both verticals → stays
  in the transport, preserving the mole wire bytes.
- `MolePestExtractionStrategy` — the **verbatim** mole `SYSTEM_PROMPT` + `claude-haiku-4-5`
  model + `VoicemailExtraction` field-mapping, moved unedited. `toDraftWorkOrder()` returns
  `null` (NMM creates no WO). The default strategy.
- `MultiTradeExtractionStrategy` — new multi-trade prompt + `MultiTradeExtraction` schema;
  `toDraftWorkOrder()` builds a DRAFT `WorkOrder` (serviceType=trade, customFields carry
  urgency/jobValueBand/callSid). A failed/empty extraction still yields a `GENERAL` DRAFT WO
  (lead never dropped).
- `VoicemailExtractionStrategyResolver` — `@Component`, indexes strategies by `verticalKey()`,
  defaults to `mole-pest` for absent/unknown `voicemailVertical` config value.
- `VoicemailLeadDetails` — common carrier (name/phone/address/summaryLine/extractedJson/
  callbackRequested + optional `draftWorkOrder`) so the Contact/Activity/notify orchestration is
  vertical-agnostic. Mole strategy reproduces the identical `extractedJson` map + summary line.
- `TwilioVoicemailService` — injects the resolver (replacing the direct
  `VoicemailExtractionService` field) + `ObjectProvider<WorkOrderService>` (field-service may be
  off). WO-create branch: if `details.draftWorkOrder() != null` AND a `WorkOrderService` is
  available → `workOrderService.create(wo)` (server-assigns number), back-fill ledger
  `createdWorkOrderId`, publish `VOICEMAIL_WORK_ORDER_DRAFTED`. Degrades to Contact+Activity+
  notify (logged, no error) when the bean is absent.
- `MissedCallInboxController` — `GET /home-services/missed-call-inbox` → DRAFT WOs whose
  `customFields.callSid != null`, newest first. Gated `@ConditionalOnProperty(home-services)` +
  `TenantModuleRegistry.requireEnabled`.

## Invariants

- **NMM byte-equivalence:** mole wire request (model `claude-haiku-4-5`, `max_tokens=512`,
  mole system prompt, user content `"Voicemail transcript:\n\n"+transcript`) byte-identical →
  `TwilioVoicemailIT` WireMock stub + `verify(1, ...)` unchanged. Mole `VoicemailExtraction`,
  `toSummaryLine`, defensive parse, `1200-1203` unchanged. Default strategy → 0 WorkOrders.
- §9: only `switchIfEmpty` in changed code is the existing genuine not-connected one; WO branch
  uses explicit-boolean / null-check, never `switchIfEmpty(create)`.
- §7: Anthropic via WireMock base-url; no live Twilio; sandbox keys.
- `WorkOrderService.create` (NOT raw `workOrders.save`) → server-assigned `workOrderNumber`.
- Error band: HS owns `4200-4219` (`4200` strategy misconfig, `4201` WO-svc-unavailable
  advisory, `4202` inbox-not-enabled). `1200-1203`/`1330`/`2530-2532` reused not re-allocated.

## Sub-steps

| Sub-step | Status | SHA | Build | Notes |
|---|---|---|---|---|
| SP1 — transport refactor + mole strategy + carrier + resolver; wire service to resolver (mole default, no WO) | done | `f919342` | TwilioVoicemailIT 6/0/0 (UNCHANGED) | byte-equivalence proven in isolation BEFORE going further |
| SP2 — MultiTrade strategy + schema + WO-create branch + ledger field + event + MissedCallInboxController + error band | done | `9e9f8e7` | compileJava OK | |
| SP3 — `HomeServicesVoicemailIT` + `…FieldServiceDisabledIT` (all 5 cases §4.8) + case-5 strategy degrade fix + openapi check | done | (this commit) | full suite GREEN | case 4 needs field-service OFF → separate class (class-level @TestPropertySource) |

## Test result — BUILD SUCCESSFUL

`./gradlew cleanTest test --tests "*TwilioVoicemailIT" --tests "*HomeServicesVoicemailIT"
--tests "*HomeServicesVoicemailFieldServiceDisabledIT" --tests "*TwilioRequestValidatorTest"
--tests "*MoleVisionServiceIT" --tests "*MoleTriageIT" --tests "*OpenApiEndpointIT"` (Docker up):

| Class | tests | failures | errors | skipped |
|---|---|---|---|---|
| `TwilioVoicemailIT` (NMM gate — UNCHANGED) | 6 | 0 | 0 | 0 |
| `HomeServicesVoicemailIT` (cases 1,2,3,5) | 4 | 0 | 0 | 0 |
| `HomeServicesVoicemailFieldServiceDisabledIT` (case 4) | 2 | 0 | 0 | 0 |
| `TwilioRequestValidatorTest` | 8 | 0 | 0 | 0 |
| `MoleVisionServiceIT` (vision regression) | 5 | 0 | 0 | 0 |
| `MoleTriageIT` (vision regression) | 7 | 0 | 0 | 0 |
| `OpenApiEndpointIT` | 2 | 0 | 0 | 0 |

**`TwilioVoicemailIT` passed UNCHANGED** (never edited; byte-equivalence held end-to-end).

### OpenAPI
`MissedCallInboxController` is `@ConditionalOnProperty(home-services)`, so — like every other
gated-module controller (`DispatchBoardController`, `WorkOrderController`, …) — it is absent from
the spec `OpenApiEndpointIT` generates (that context runs with opt-in modules OFF). Semantic
order-insensitive compare of generated vs committed `docs/api/openapi.json`: **222 == 222 paths,
152 == 152 schemas, zero added/removed** — HS-1 adds nothing to the documented surface. The only
non-deterministic byte churn `verifyOpenApi` produced was an operationId-suffix reordering on the
unrelated `/integrations/mole-triage/tokens` path (the documented springdoc non-determinism;
`verifyOpenApi` is advisory), so `docs/api/openapi.json` was left at HEAD. `OpenApiEndpointIT`
passes (2/0/0).

§9: the only `switchIfEmpty` in changed code is the pre-existing genuine not-connected
`verifiedConnection` + the existing `findOrCreateContact` explicit-boolean (unchanged); the
WO-create branch uses an explicit null/Optional check, never `switchIfEmpty(create)`.
