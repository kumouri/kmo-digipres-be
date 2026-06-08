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

---

# HS-2 — Equipment-nameplate photo vision enrichment — Progress Ledger

> Branch `home-services-front-desk-phase-2-equipment-vision` (off `main` @ `e056c7d`, HS-1 merged).
> Spec: `~/.claude/plans/home-services-front-desk.md` §3 (HS-2), §7 (reuse map "Vision nameplate"),
> §4.5 (error band — HS-2 claims `4210-4214`), §6 (risks).

## Goal

After a home-services voicemail creates a DRAFT `WorkOrder` (HS-1), let the caller upload a photo of
the equipment; read the nameplate via the **already-shipped `AiVisionService.extract`** with a
nameplate prompt → `{make, model, serial, equipmentType, observedSymptom}`; store the photo as an
`Attachment(subjectType="WORK_ORDER")`; enrich the DRAFT WorkOrder (notes + customFields) + log an
`Activity(NOTE, WORK_ORDER)` + enrich the owner digest. **Best-effort throughout** — a vision/budget/
upstream failure enriches nothing but never errors the request or drops/corrupts the lead.

## DESIGN FORK — tokenized upload link (chosen), NOT an inbound-MMS webhook

The photo arrives over a **tokenized HTTPS upload**, reusing the proven `MoleTripwireService` +
entity-bound-token pattern (HMAC token carrying `tenantId + workOrderId` → public multipart endpoint
→ store `Attachment` → vision → enrich the bound WorkOrder). Rationale:
- **No new inbound Twilio MMS webhook, no added 10DLC surface.** The only inbound Twilio handler
  (`TwilioVoicemailController`) is voice/transcription-only; there is no clean inbound-SMS/MMS handler
  to extend. A net-new MMS webhook would add a 10DLC-registered campaign dependency (plan §6 risk) for
  no gain over the link.
- **Trivial correlation:** the token encodes the target WorkOrder, so the caller's photo binds to
  *their* job with zero guessing. Tenant + WorkOrder come from the token **only**, never the payload.
- **Exact precedent:** `MoleTripwireService` is the `classify`-shaped, Project-targeted twin; HS-2 is
  its `extract`-shaped, WorkOrder-targeted twin. `EquipmentPhotoTokenService` is the additive sibling
  of `MoleTripwireTokenService`/`PublicWidgetTokenService` (those cores stay empty-diff).
- The HS-1 caller auto-ack SMS carries the link (gated strictly behind the home-services vertical —
  appended only when a DRAFT WO was created AND `kmosf.home-services.equipment-upload-base-url` is
  set, default empty → the mole/NMM auto-ack body is **byte-unchanged**; `TwilioVoicemailIT` is the
  gate).

## Files

**Created (new `integration/equipmentvision/` package):**
- `EquipmentReading.java` — defensive parse of the open-schema vision JSON → `{make, model, serial,
  equipmentType, observedSymptom}`; `isEmpty()`, `toFieldMap()`, `toSummaryLine()` (never throws).
- `EquipmentPhotoToken.java` + `EquipmentPhotoTokenService.java` — HMAC token carrying
  `tenantId|widgetType|workOrderId|expiry` (the `MoleTripwireTokenService` 4-field format, WorkOrder
  as the entity); reuses `kmosf.security.widget-token-secret` + `1600-1603` rejection codes.
- `EquipmentPhotoResponse.java` — public result `{enriched, make, model, serial, message, attachmentId}`.
- `EquipmentVisionService.java` — the store→`extract`→enrich→notify pipeline (the `MoleTripwireService`
  twin). `@ConditionalOnProperty(home-services)`. Best-effort vision; loads WO tenant-scoped (`4212`
  if gone); merges nameplate fields into WO `customFields` + appends a notes line; `Activity(NOTE,
  WORK_ORDER)`; owner digest; `EQUIPMENT_PHOTO_READ` event.

**Created (`module/homeservices/`):**
- `controller/EquipmentPhotoController.java` — public `POST /public/integrations/home-services/
  equipment-photo/{token}/upload` (multipart via `getMultipartData()`, the `MoleTripwireController`
  pattern; missing image part → `4213`). `@ConditionalOnProperty(home-services)`.
- `controller/EquipmentPhotoTokenController.java` — ADMIN `POST /home-services/equipment-photo/tokens/
  {workOrderId}` (the `MoleTripwireTokenController` `{projectId}` twin; loads WO tenant-scoped first →
  `4214` if not found; 7-day TTL). `@ConditionalOnProperty(home-services)` + `requireEnabled` +
  `requireRole("ADMIN")`.
- `EquipmentVisionItStorageTestConfig.java` (test support) — in-memory `FileStorageService`
  `@Bean @Primary` stub (the `MoleTripwireItStorageTestConfig` clone).
- `EquipmentVisionIT.java` (test) — 6 cases (below).

**Modified (surgical):**
- `automation/DomainEventType.java` — add `EQUIPMENT_PHOTO_READ` (advisory).
- `controller/advice/GlobalErrorHandler.java` — document the `4210-4214` HS-2 band.
- `integration/twilio/voice/TwilioVoicemailService.java` — inject `EquipmentPhotoTokenService` +
  `kmosf.home-services.equipment-upload-base-url`; the auto-ack appends the upload link **only** for
  the home-services vertical (WO present) + when the base URL is set. Mole/NMM path byte-unchanged.

## Error band (plan §4.5 — HS-2 claims `4210-4214`)

`4210` token widgetType mismatch (401); `4211` unsupported image media type (415, shared
`AiVisionService.isSupportedMediaType`); `4212` token's WorkOrder gone (404, public upload); `4213`
missing image part (400); `4214` WorkOrder not found for token issuance (404, ADMIN). Reused (NOT
re-allocated): `1200-1203` (AI — best-effort, never surfaced), `1600-1603` (token), `1310` (storage),
`1300` (Activity), `1800` (not-ADMIN), `2530-2532` (Twilio SMS).

## Invariants

- **Best-effort:** `AiVisionService.extract` already degrades to `{}` on any failure; an additional
  `onErrorResume` in `readNameplate` catches an upstream-thrown budget/missing-key (`1200`/`1203`)
  before that fallback. Either way → empty `EquipmentReading` → 200, `enriched=false`, WO untouched.
- **Never drops/corrupts the lead:** the photo `Attachment` is always stored; a failed/blank read
  makes zero WO/Activity mutation; the upload always returns 200.
- §9: the only `switchIfEmpty` in the package is the genuine WO not-found (`4212`); the blank-read
  branch is an explicit `if (reading.isEmpty())`, never `switchIfEmpty`.
- §7: Anthropic via WireMock; Twilio SMS + email via `@MockitoBean`; in-memory storage stub; sandbox
  keys; no live charge/send/upload.
- NMM byte-equivalence held: `TwilioVoicemailIT` 6/0/0 UNCHANGED (the auto-ack link is gated behind
  the home-services vertical + an unset-by-default base URL).

## Sub-steps

| Sub-step | Status | SHA | Build | Notes |
|---|---|---|---|---|
| SP1 — equipmentvision package (reading, token, service, response) + HS controllers + event + error band | done | (this commit) | compileJava OK | tokenized-link fork; `AiVisionService.extract` reused as-is |
| SP2 — auto-ack carries the upload link, gated behind the HS vertical (byte-unchanged mole path) | done | (this commit) | compileJava OK | base URL default empty → no link → NMM IT byte-identical |
| SP3 — `EquipmentVisionIT` (6 cases) + storage stub + full gate-suite re-run | done | (this commit) | full suite GREEN | |

## Test result — BUILD SUCCESSFUL

`./gradlew cleanTest test --tests "*EquipmentVisionIT" --tests "*TwilioVoicemailIT"
--tests "*HomeServicesVoicemailIT" --tests "*HomeServicesVoicemailFieldServiceDisabledIT"
--tests "*MoleTriageIT" --tests "*MoleVisionServiceIT" --tests "*MoleTripwireIT"
--tests "*OpenApiEndpointIT"` (Docker up):

| Class | tests | failures | errors | skipped |
|---|---|---|---|---|
| `EquipmentVisionIT` (HS-2, new) | 6 | 0 | 0 | 0 |
| `TwilioVoicemailIT` (NMM gate — UNCHANGED) | 6 | 0 | 0 | 0 |
| `HomeServicesVoicemailIT` (HS-1) | 4 | 0 | 0 | 0 |
| `HomeServicesVoicemailFieldServiceDisabledIT` (HS-1) | 2 | 0 | 0 | 0 |
| `MoleTriageIT` (vision regression) | 7 | 0 | 0 | 0 |
| `MoleVisionServiceIT` (vision regression) | 5 | 0 | 0 | 0 |
| `MoleTripwireIT` (entity-bound-token regression) | 6 | 0 | 0 | 0 |
| `OpenApiEndpointIT` | 2 | 0 | 0 | 0 |

`EquipmentVisionIT` cases: (1) legible nameplate → WO customFields+notes enriched, `Activity(NOTE,
WORK_ORDER)`, owner notify, **image content block hit WireMock** (`messages[0].content[0].type==image`),
`EQUIPMENT_PHOTO_READ` enriched=true; (2) vision 500 → 200 best-effort, WO customFields/notes
UNCHANGED, photo stored, no Activity; (3) blank/all-null read → 200 enriched=false, WO untouched, no
Activity; (4) missing image part → 400/`4213`; (5) wrong-widgetType token → 401/`4210`, zero effect;
(6) tampered token → 401 (`1600`-range), zero effect.

### OpenAPI
`EquipmentPhotoController` + `EquipmentPhotoTokenController` are `@ConditionalOnProperty(home-services)`,
so — like every other gated-module controller — they are absent from the spec `OpenApiEndpointIT`
generates (that context runs with opt-in modules OFF). `docs/api/openapi.json` unchanged at HEAD;
`OpenApiEndpointIT` passes (2/0/0). Confirms the HS-1 note: gated opt-in-module controllers add
nothing to the generated surface.

---

# HS-3 — EMERGENCY live-forward + two-sided outbound — Progress Ledger

> Branch `home-services-front-desk-phase-3-emergency-forward` (off `main` after HS-1 + HS-2 merged).
> Spec: `~/.claude/plans/home-services-front-desk.md` §3 (HS-3), §6 (live-forward TIMING risk — the
> design driver), §4.5 (error band `4215-4219`), §7 (reuse map "EMERGENCY forward + SMS").

## The §6 timing problem and the design that resolves it

AI urgency (`EMERGENCY`) is known only **after** transcription+triage — which is **after** the caller
has already left a voicemail — so the live-forward **cannot** be AI-gated mid-call. HS-3 therefore
uses a **deterministic IVR gate on the voice webhook** (not the transcription path):

1. **Emergency live-forward (voice webhook, before voicemail).** When the tenant's Twilio
   `IntegrationConnection.config.onCallPhone` is set, `handleVoice` returns a brief
   `<Gather numDigits="1" timeout=… action="voice/gather"><Say>…press 1 for the on-call tech…</Say></Gather>`
   followed by the existing greeting + `<Record>` (so no-input falls through to voicemail). A NEW
   signature-verified callback `POST /public/integrations/twilio/{id}/voice/gather` handles the digit:
   `Digits=="1"` → `<Dial>onCallPhone</Dial>` (the live-forward); anything else / no input / timeout →
   the existing greeting + `<Record>` TwiML. **When `onCallPhone` is absent (NMM/default), `handleVoice`
   returns the EXISTING greeting+record TwiML BYTE-UNCHANGED — the gate.**
2. **Two-sided outbound (after voicemail+triage).** Caller booking-link SMS: when
   `config.bookingLinkUrl` is set, the caller auto-ack appends `" Book your visit: <url>"` (gated;
   composes with HS-2's equipment-upload-link append; mole/default auto-ack stays byte-identical).
   Owner triage digest: `notifyRob` prepends an `EMERGENCY` flag to the email subject + email body +
   SMS **only** when the AI triage urgency (`extractedJson.urgency`) is `EMERGENCY` (gated;
   mole/default owner-notify byte-unchanged).

## Invariants

- **NMM/mole byte-equivalence:** no `onCallPhone` → voice TwiML byte-identical; no `bookingLinkUrl` →
  auto-ack byte-identical; non-EMERGENCY urgency → owner-notify byte-identical. `TwilioVoicemailIT`,
  `HomeServicesVoicemailIT`, `HomeServicesVoicemailFieldServiceDisabledIT`, `MoleTriageIT`,
  `MoleVisionServiceIT`, `MoleTripwireIT`, `EquipmentVisionIT` all stay green; NMM IT not edited.
- The `/voice/gather` callback is signature-verified exactly like voice/voicemail (reused
  `verifiedConnection` → `4000-4003`). Error band `4215-4219` RESERVED (no new condition thrown today);
  `2530-2532` (SMS) reused not re-allocated.
- §7 telephony fully stubbed — tests assert TwiML structure (`<Gather>`, `<Dial>onCallPhone</Dial>`,
  the fall-through `<Record>`) + the stubbed `TwilioSmsService` bodies. No real call/SMS/forward.

## Files

| File | Change |
|---|---|
| `integration/twilio/voice/TwilioVoicemailService.java` | `handleVoice` gated on `onCallPhone` (`buildVoiceTwimlFor`); new `handleGather` + `buildEmergencyGatherTwiml` + `buildDialTwiml`; `notifyRob` EMERGENCY-flag (gated); `buildAutoAckBody`/`autoAckCaller` booking-link (gated); `CONFIG_ON_CALL_PHONE`/`CONFIG_BOOKING_LINK_URL` constants + `configValue` helper + 2 `@Value` (prompt/timeout) |
| `controller/integration/TwilioVoiceController.java` | new `POST /{tenantId}/voice/gather` endpoint (sig-verified, tenant-from-path, `4003` on bad id) |
| `controller/advice/GlobalErrorHandler.java` | HS-3 error-band Javadoc (`4215-4219` reserved; go-live 10DLC note) |
| `docs/api/openapi.json` | refreshed via `verifyOpenApi` — adds the `voice/gather` path (`operationId: gather`); existing voice/voicemail paths unchanged |
| `integration/twilio/voice/HomeServicesEmergencyForwardIT.java` | NEW IT (7 cases) |

## Sub-steps

| Sub-step | Status | Build | Notes |
|---|---|---|---|
| SP1 — IVR gate on `handleVoice` + `handleGather` + `/voice/gather` controller + Dial/Gather TwiML | done | compileJava OK | `onCallPhone` gate; no-config TwiML byte-unchanged |
| SP2 — two-sided outbound: owner EMERGENCY digest + caller booking-link (both gated, byte-unchanged default) | done | compileJava OK | composes with HS-2 upload link |
| SP3 — `HomeServicesEmergencyForwardIT` (7 cases) + error band + openapi refresh + full gate-suite re-run | done | full suite GREEN | |

## Test result — BUILD SUCCESSFUL

`./gradlew cleanTest test --tests "*HomeServicesEmergencyForwardIT" --tests "*TwilioVoicemailIT"
--tests "*HomeServicesVoicemailIT" --tests "*HomeServicesVoicemailFieldServiceDisabledIT"
--tests "*EquipmentVisionIT" --tests "*MoleTriageIT" --tests "*MoleVisionServiceIT"
--tests "*MoleTripwireIT" --tests "*OpenApiEndpointIT"` (Docker up):

| Class | tests | failures | errors | skipped |
|---|---|---|---|---|
| `HomeServicesEmergencyForwardIT` (HS-3, new) | 7 | 0 | 0 | 0 |
| `TwilioVoicemailIT` (NMM gate — UNCHANGED) | 6 | 0 | 0 | 0 |
| `HomeServicesVoicemailIT` (HS-1) | 4 | 0 | 0 | 0 |
| `HomeServicesVoicemailFieldServiceDisabledIT` (HS-1) | 2 | 0 | 0 | 0 |
| `EquipmentVisionIT` (HS-2) | 6 | 0 | 0 | 0 |
| `MoleTriageIT` (vision regression) | 7 | 0 | 0 | 0 |
| `MoleVisionServiceIT` (vision regression) | 5 | 0 | 0 | 0 |
| `MoleTripwireIT` (entity-bound-token regression) | 6 | 0 | 0 | 0 |
| `OpenApiEndpointIT` | 2 | 0 | 0 | 0 |

`HomeServicesEmergencyForwardIT` cases: (a) `onCallPhone` set → `/voice` returns the emergency
`<Gather numDigits="1" action="voice/gather">` (no `<Dial>` yet, falls through to `<Record>`);
(b) `/voice/gather` `Digits=1` → `<Dial>+1618…</Dial>` (no `<Record>`/`<Gather>`); (c) `/voice/gather`
no input → falls through to the **exact** record-voicemail TwiML (captured baseline, byte-equal);
(d) `/voice/gather` other digit (`9`) → same record TwiML byte-equal; (e) `/voice` with NO
`onCallPhone` → record TwiML **byte-identical** to the captured baseline, no `<Gather>`/`<Dial>`
(the NMM gate); (f) EMERGENCY voicemail (+`onCallPhone`+`bookingLinkUrl`) → owner email subject &
body & SMS EMERGENCY-flagged AND caller auto-ack carries `Book your visit: <link>`; (g) URGENT
voicemail, no booking config → owner digest NOT flagged + caller auto-ack carries NO booking link
(the gates).

> **Byte-equivalence note:** the record-voicemail TwiML is captured at runtime from a no-`onCallPhone`
> `/voice` response (`baselineRecordTwiml()`) rather than a hard-coded literal — config-independent,
> so it pins the gather fall-through + no-gate paths to the *actual* deployed greeting bytes
> (`application.properties` ships a mole-pest greeting, not the service default).

### OpenAPI
Unlike the HS-2 controllers (gated `@ConditionalOnProperty(home-services)`, absent from the spec),
the `/voice/gather` endpoint lives on `TwilioVoiceController` — gated
`@ConditionalOnProperty(voicemail-intake, matchIfMissing=true)`, so it is **present by default** in the
generated spec (like the existing `/voice` + `/voicemail` webhook paths). `OpenApiEndpointIT` passes
(2/0/0); `verifyOpenApi` refreshed `docs/api/openapi.json` to add the `voice/gather` path
(`operationId: gather`, tag `twilio-voice-controller`) — a single net-changed line, the existing
voice/voicemail paths unchanged, cp1252 intact.

## Go-live telephony TODO (out of the implementation loop — §7 / §6)

Telephony is fully stubbed in tests. Real go-live requires, as separate human/external steps:
- a **real Twilio phone number** pointed at `/public/integrations/twilio/{id}/voice`;
- a **verified on-call number** set in `config.onCallPhone` (E.164) — Twilio `<Dial>` is a voice
  action, no 10DLC, but the destination must be reachable;
- an **A2P 10DLC campaign** approved for the **caller-facing** booking-link SMS (application-to-person
  traffic to US numbers is carrier-filtered until the campaign is registered). The owner-notify SMS
  (to the business's own phone) is lower-risk; the booking-link SMS is the 10DLC-gated piece.

---

# CF-1 — ChairFill no-show risk model + `BOOKING_RISK_SCORED` — Progress Ledger

> Branch `chairfill-salon-flagship-phase-1-noshow-risk` (off `main` after the Home Services flagship
> merged). Spec: `~/.claude/plans/chairfill-salon-flagship.md` §3 (CF-1 detail), D1 (no-show model
> fork), D3 (module enablement), §3.3 (ITs), error band `4220-4224`.

## Goal

A nightly, per-tenant, ML no-show-risk score stamped on each **upcoming** salon `Booking`, emitting
`BOOKING_RISK_SCORED`. A new **chairfill** module rides the shipped salon-spa module. **The shipped
lead-scoring (`LeadScoringV2Service`/`LeadScore`/`LeadScoringJob`) and NMM are UNTOUCHED** — CF-1 is a
*parallel* service/model, chairfill-module-gated (default OFF). Blast radius zero.

## Design as built (mirror of `LeadScoringV2Service`, D1)

- **`NoShowRiskScoringService`** (new `module/chairfill/scoring/`) — same machine as the lead-scorer:
  nightly `@Scheduled(cron=…02:30)` per-tenant; a `>= MIN_BOOKINGS_FOR_MODEL (40)`-sample
  model-vs-rules gate; `smile.classification.LogisticRegression.fit(x,y)` + `predict(f,posterior)`;
  a `NoShowScoringJob` ledger (PENDING→RUNNING→DONE/FAILED); the CPU work on
  `Schedulers.boundedElastic()` via `Mono.fromCallable`; emits the domain event per scored booking.
  Hand-wired as a `@Bean` in `ChairFillAutoConfiguration` (the salon-spa `@Bean` pattern), so it does
  not exist unless `kmosf.modules.chairfill.enabled=true`.
- **Scored entity:** upcoming `Booking` (`CONFIRMED|PENDING_DEPOSIT`, `scheduledStart > now`).
- **Training label (per *terminal* booking):** `NO_SHOW = 1` / `COMPLETED = 0`; CANCELLED excluded.
  Predicted score = P(no-show). *Label polarity is the mirror-image of the lead-scorer's WON=1 —
  documented in code + the `NoShowRisk` Javadoc to avoid a sign bug.*
- **Feature vector (8, all derivable from `Booking` + `ServiceMenuItem` + history):**
  `[priorNoShowRate, priorBookingCount, leadTimeHours, dayOfWeek, hourOfDay, priceBand, depositOnFile,
  daysSinceLastVisit]`. Training features are computed **as-of each terminal booking's scheduledStart
  using only strictly-earlier history** (no future leakage); scoring features use the contact's full
  terminal history as-of now. The model trains only when `>= 40` terminal bookings AND both classes
  are present (a single-class set can't fit a `LogisticRegression`).
- **Tiers:** new `NoShowRisk(riskScore, riskTier∈{LOW,MEDIUM,HIGH}, source, computedAt)` embedded
  nullable on `Booking` (the `Contact.leadScore` precedent). Thresholds `>=0.6 HIGH, >=0.35 MEDIUM,
  else LOW`, config-tunable (`kmosf.chairfill.noshow-scoring.{high,medium}-threshold`).
- **Cold-start rules (the night-one path), evaluated in priority order:** HIGH if
  `priorNoShowRate >= 0.34` OR (`leadTimeHours > 336h` AND no deposit) — checked *first* so a far-out
  un-deposited booking is HIGH even with no history; then INSUFFICIENT_DATA→LOW for a first-timer with
  no prior bookings, no prior visit, and no deposit (never punish zero evidence); then MEDIUM for
  `priorNoShowRate > 0` OR a *real* lapsed client (`priorBookingCount > 0` AND `daysSinceLastVisit >
  90` — gated on real history so the 365-day no-visit sentinel never trips it); else LOW.

## Invariants

- **Lead-scoring untouched + regression-proof:** zero edit to `LeadScoringV2Service`/`LeadScore`/
  `LeadScoringJob`; `LeadScoringV2IT` (4) + `LeadScoringControllerIT` (3) pass UNCHANGED. A dedicated
  `leadScoringUntouched` CF-1 test asserts a no-show run writes no `LeadScoringJob` / `Contact`.
- **Blast radius zero (D3):** the chairfill module is `@ConditionalOnProperty` (default OFF) +
  `@ConditionalOnBean(SalonBookingService.class)` (no-ops if salon-spa is off) + `Tenant.enabledModules`
  membership; `nightlyRun` filters to ACTIVE tenants whose `enabledModules` contains `"chairfill"`. A
  `moduleDisabled_skipsTenant` test proves a non-chairfill tenant is skipped (no stamp, no job).
- **Cold-start:** under 40 terminal bookings a salon gets the transparent rules fallback (source
  surfaced) so a brand-new salon gets value night one.
- **Reactive/blocking discipline:** Smile train/predict + the feature grouping run on
  `boundedElastic`, never the Netty loop (the lead-scorer precedent).
- **Error band `4220-4224`:** `4220` chairfill-not-enabled (via shared `requireEnabled` 1130/1132);
  `4221` retrain-already-running (409); `4222-4224` reserved. Scoring is pure ML — no AI-budget
  (1200-1203) path, zero per-booking token cost.
- The `Booking.noShowRisk` field is additive + nullable; only upcoming bookings are saved+emitted
  (terminal bookings are never re-stamped — `onlyUpcomingScored_terminalUntouched` proves it).

## Files

**Created:**
- `module/chairfill/ChairFillAutoConfiguration.java` (module key `"chairfill"`, `ModuleDefinition`,
  `@Bean NoShowRiskScoringService` gated `@ConditionalOnBean(SalonBookingService.class)`).
- `module/chairfill/model/{NoShowRisk, NoShowRiskTier, NoShowScoringJob}.java`.
- `repository/NoShowScoringJobRepository.java` (mirror of `LeadScoringJobRepository`).
- `module/chairfill/scoring/NoShowRiskScoringService.java` (the fork).
- `module/chairfill/controller/NoShowRiskController.java` (`@ConditionalOnProperty` + `requireEnabled`;
  `POST /chairfill/risk/retrain`, `GET /chairfill/risk/bookings?from&to`).
- `src/test/.../module/chairfill/NoShowRiskScoringIT.java` (8 cases).

**Modified (surgical, additive):**
- `module/salonspa/model/Booking.java` — add nullable `NoShowRisk noShowRisk`.
- `module/salonspa/repository/BookingRepository.java` — add `findAllByTenantId` +
  `findByTenantIdAndScheduledStartBetween` (explicit-param derived queries; the nightly job has no
  request context).
- `module/salonspa/repository/ServiceMenuRepository.java` — add `findAllByTenantId`.
- `automation/DomainEventType.java` — add `BOOKING_RISK_SCORED` (advisory).
- `controller/advice/GlobalErrorHandler.java` — document the `4220-4224` band.
- `META-INF/spring/…AutoConfiguration.imports` — register `ChairFillAutoConfiguration`.
- `application.properties` — `kmosf.modules.chairfill.enabled` global toggle (default false).

## Sub-steps

| Sub-step | Status | Build | Notes |
|---|---|---|---|
| SP1 — model + repo + Booking field + event + error band + module skeleton + scorer + controller | done | compileJava OK | mirror of LeadScoringV2Service; chairfill bean gated |
| SP2 — `NoShowRiskScoringIT` (8 cases) + rules-ordering fix (HIGH-before-no-history; lapsed gated on real history) + full gate-suite re-run | done | full suite GREEN | context-free `mongo.findById` reads (BookingRepository.findById auto-filters) |

## Test result — BUILD SUCCESSFUL

`./gradlew cleanTest test --tests "*NoShowRiskScoringIT" --tests "*LeadScoring*"
--tests "*SalonBooking*IT" --tests "*OpenApiEndpointIT"` (Docker up). `*SalonBooking*IT` matched no
class — **the salon-spa module shipped with no dedicated ITs**, so `NoShowRiskScoringIT` (which seeds,
re-saves, and reads `Booking`s with the new field) is the de-facto Booking-serialization regression
proof, alongside the full app-context boot it forces.

| Class | tests | failures | errors | skipped |
|---|---|---|---|---|
| `NoShowRiskScoringIT` (CF-1, new) | 8 | 0 | 0 | 0 |
| `LeadScoringV2IT` (lead-scorer regression — UNCHANGED) | 4 | 0 | 0 | 0 |
| `LeadScoringControllerIT` (lead-scorer regression — UNCHANGED) | 3 | 0 | 0 | 0 |
| `OpenApiEndpointIT` | 2 | 0 | 0 | 0 |
| **TOTAL** | **17** | **0** | **0** | **0** |

`NoShowRiskScoringIT` cases: (1) `tenantWith40TerminalBookings_usesModel` — 50 terminal bookings (both
classes) → upcoming bookings score `source=MODEL`, riskScore∈[0,1]; (2) `tenantBelowThreshold_usesRulesFallback`
— a prior-no-show contact → HIGH (rules), a new-with-deposit booking → LOW, never `MODEL`; (3)
`coldStartLongLeadNoDeposit_isHigh_andFirstTimerNoEvidence_isLow` — 20-day-out no-deposit first-timer →
HIGH, short-lead no-evidence first-timer → INSUFFICIENT_DATA/LOW; (4) `scoresStableAcrossRuns` — two
runs give identical riskScore (rules determinism); (5) `emitsBookingRiskScored` — one event per scored
booking with payload keys {bookingId, contactId, staffMemberId, riskTier, riskScore, source}; (6)
`onlyUpcomingScored_terminalUntouched` — COMPLETED/NO_SHOW/CANCELLED bookings keep `noShowRisk==null`;
(7) `moduleDisabled_skipsTenant` — a tenant without `"chairfill"` in enabledModules is skipped by
`nightlyRun` (no stamp, no job for that tenant); (8) `leadScoringUntouched` — the run writes no
`LeadScoringJob`/`Contact` for the tenant.

### OpenAPI
`NoShowRiskController` is `@ConditionalOnProperty(chairfill)`, so — like every other gated opt-in-module
controller — it is absent from the spec `OpenApiEndpointIT` generates (that context runs with chairfill
OFF; verified `grep -c chairfill build/openapi/openapi.json` == 0). `docs/api/openapi.json` unchanged at
HEAD; `OpenApiEndpointIT` passes (2/0/0).

### Deviations / surprises
- **No salon-spa ITs exist on `main`.** The plan's `--tests "*SalonBooking*IT"` gate matched zero
  classes (confirmed by globbing `src/test`). The new CF-1 IT exercises Booking persistence with the
  added field, covering the serialization-regression concern the gate intended; reported as the
  honest state rather than inventing a salon IT.
- **`@CreatedDate` auditing overwrites a pre-set `createdAt`.** Spring Data stamps `createdAt` on
  insert regardless of the supplied value, which would clamp `leadTimeHours` to 0 for past-dated
  seed bookings. The IT patches `createdAt` via a direct `mongo.updateFirst` after save so the feature
  vector (and the determinism test) are honest. Production is unaffected (real bookings' createdAt is
  genuinely their creation instant).
- **`BookingRepository.findById` auto-tenant-filters** (the `TenantScopedSimpleReactiveMongoRepository`
  override) and so `required()`s a request context the test doesn't have → the IT reads via
  `mongo.findById(id, Booking.class)`. The nightly job itself reads via the explicit-param
  `findAllByTenantId` (added), which bypasses the auto-filter — the lead-scorer precedent.

---

# CF-2 — ChairFill risk-tiered prevention + Claude-personalized reminder — Progress Ledger

> Branch `chairfill-salon-flagship-phase-2-risk-prevention` (off `main` after CF-1 merged @ `4c28013`).
> Spec: `~/.claude/plans/chairfill-salon-flagship.md` CF-2 section + D5 (dedicated subscriber, NOT a
> seeded `SEND_SMS` rule), error band `4225-4229`.

## Goal

On `BOOKING_RISK_SCORED` (the CF-1 stamp): a **HIGH**-risk upcoming booking → **require a deposit**
(reuse the shipped salon deposit/Stripe path) + an **extra confirmation** SMS; a **LOW/MEDIUM** booking
→ a single **light reminder**. The reminder copy is **Claude-personalized** (stylist name, last service,
the owner's brand tone) via an `AnthropicAiAssistService`-mirror, **best-effort** (a Claude/budget
failure falls back to a generic template, never blocks). TCPA-safe; salon-spa core / CF-1 / NMM
untouched; blast radius zero.

## Design as built (mirror of `RebookingNudgeService`, D5)

- **`RiskTieredPreventionService`** (new `module/chairfill/automation/`) — a `@PostConstruct`
  `events.stream().filter(BOOKING_RISK_SCORED).flatMap(handle)` subscriber (the `RebookingNudgeService`
  pattern) on `Schedulers.boundedElastic()`, per-event `onErrorResume` → empty (a poison event never
  breaks the stream). Synthetic `TenantContext(tenantId, null, {SYSTEM})`. A visible-for-test
  `handle(event)` returns `Mono<Void>` the IT blocks (the `CoverageNudgeJob.nudgeDueOnce()` posture).
  - **Why a dedicated subscriber, not a `SEND_SMS` WorkflowRule (D5):** the generic `SEND_SMS` action
    resolves a *static* template (no per-contact Claude personalization) and there is no
    `REQUIRE_DEPOSIT` action type. So the personalized + deposit logic lives here; an owner-tunable
    static baseline rule is *also* seeded (below) so the owner sees a toggle.
  - **HIGH** → `SalonBookingService.requireDepositNow(bookingId, amount)` (deposit amount = service
    price × `deposit-rate` (default 0.25), floored at `deposit-min` (default $20); an already-set
    `depositAmount` wins) + an **extra-confirmation** Claude SMS (`confirm=true` → "reply to confirm").
  - **LOW/MEDIUM** → one light Claude-personalized reminder SMS.
- **`ReminderCopyService`** (new `module/chairfill/ai/`) — a **sibling of `GbpReplyDraftService`**
  (NOT an edit to the reused `AnthropicAiAssistService`): per-tenant Anthropic key + house-key fallback,
  `AiUsageRecorder.checkBudget()` BEFORE + `record()` AFTER, WireMock-able base-url, defensive parse,
  Haiku default. A salon-reminder system prompt that names the stylist + last service in the owner's
  brand tone and outputs ONLY the SMS body; a blank answer → 1202 (so the caller falls back). Codes
  `1200-1203` reused unchanged.
- **`ChairFillReminderAutomation`** (new) — the `OnTheWaySmsAutomation` clone: idempotent
  `ApplicationReadyEvent` seeder of an owner-tunable baseline `SEND_SMS` `WorkflowRule` on
  `BOOKING_RISK_SCORED` per chairfill tenant (D5's "WorkflowRule-driven" intent; the personalized path
  stays in the subscriber). Honest v1 caveat documented: the CF-1 payload carries no resolved phone, so
  the generic dispatcher safely skips the static rule — it is a visible/editable placeholder.
- **`SalonBookingService.requireDepositNow(bookingId, amount)`** — the ONLY salon-core change: a new
  additive method that requires a deposit on an *already-created* booking by reusing the exact create-time
  path (`createDepositInvoice` → DRAFT `Invoice` via `InvoiceRepository`). Idempotent + safe: no-op on a
  terminal booking, when a deposit invoice already exists, or on a null/non-positive amount. Existing
  `create`/`confirm`/`complete`/`cancel` callers are byte-unchanged.

## TCPA / consent + frequency cap (plan §4 risk, HARD GATE 4)

- **Opt-out / STOP:** a Contact carrying the `sms-opt-out` tag (`RiskTieredPreventionService.SMS_OPT_OUT_TAG`)
  gets NO SMS — the codebase has no `smsOptIn` boolean (consent is tag/subscription-based today), so the
  honored signal is a tag an inbound-STOP handler / staff toggle sets. A contact with no phone is skipped.
- **Per-contact frequency cap:** the `ReminderLog` ledger backs a rolling-window count
  (`max-per-contact-per-window` default 2 over `window-hours` default 24) so a HIGH-risk client with
  several upcoming bookings is not spammed.
- **Idempotency:** the `ReminderLog` row is inserted **FIRST** (unique `(tenantId, bookingId)`), so a
  re-fired `BOOKING_RISK_SCORED` (nightly re-score / restart / concurrent emit) loses on a
  `DuplicateKeyException` → ZERO duplicate SMS/deposit (the `CoverageNudgeLog` ledger-insert-FIRST pattern).

## Invariants

- **Best-effort + safe (HARD GATE 2):** every external call is `onErrorResume`-wrapped — a Claude /
  budget / missing-key (1200/1202/1203) failure degrades to a generic template; an SMS failure (no
  Twilio connection 2501, send 2531/2532) or a deposit-mint failure logs and degrades. A booking is
  never dropped or corrupted.
- **Blast radius zero (HARD GATE 3):** the beans are `@ConditionalOnProperty(chairfill)` +
  `@ConditionalOnBean(SalonBookingService.class)`; `BOOKING_RISK_SCORED` is only emitted by the CF-1
  scorer for chairfill tenants; AND `process` re-checks `Tenant.enabledModules` membership
  (defense-in-depth) → a hard no-op for every non-chairfill tenant regardless of who emits.
- **No new error codes (band 4225-4229 RESERVED, HARD GATE 5):** CF-2 is fully additive + best-effort.
  Reused unchanged: AI `1200-1203`, Twilio `2530-2532` (+ `2501`), salon deposit/booking `2900`.
- **Salon-spa core / CF-1 / NMM untouched:** the only salon-core edit is the additive
  `requireDepositNow`; `NoShowRiskScoringIT` (8) + `LeadScoringV2IT` (4) + `OpenApiEndpointIT` (2) pass
  unchanged.

## Files

**Created:**
- `module/chairfill/ai/ReminderCopyService.java` (the Claude-personalized reminder drafter; `GbpReplyDraftService` sibling).
- `module/chairfill/automation/RiskTieredPreventionService.java` (the `BOOKING_RISK_SCORED` subscriber).
- `module/chairfill/automation/ChairFillReminderAutomation.java` (owner-tunable baseline `WorkflowRule` seeder).
- `module/chairfill/automation/ReminderLog.java` + `ReminderLogRepository.java` (idempotency + frequency-cap ledger).
- `src/test/.../module/chairfill/RiskTieredPreventionIT.java` (5 cases).

**Modified (surgical, additive):**
- `module/salonspa/service/SalonBookingService.java` — add `requireDepositNow(bookingId, amount)` (reuses `createDepositInvoice`).
- `module/chairfill/ChairFillAutoConfiguration.java` — register the 3 CF-2 beans (all `@ConditionalOnBean(SalonBookingService.class)`).
- `controller/advice/GlobalErrorHandler.java` — document the `4225-4229` band (mints nothing; reuse map).

## Sub-steps

| Sub-step | Status | Build | Notes |
|---|---|---|---|
| SP1 — `requireDepositNow` (salon-core additive) + `ReminderCopyService` + `ReminderLog`/repo + `RiskTieredPreventionService` + `ChairFillReminderAutomation` + bean wiring + 4225-4229 doc | done | compileJava OK | mirror of `RebookingNudgeService` + `GbpReplyDraftService`; deposit path reused |
| SP2 — defense-in-depth tenant-module guard in `process` + `RiskTieredPreventionIT` (5 cases) + full gate-suite re-run | done | full suite GREEN | `@MockitoBean TwilioSmsService` capture; WireMock-Anthropic; `handle(event)` driven |

## Test result — BUILD SUCCESSFUL

`./gradlew cleanTest test --tests "*RiskTieredPreventionIT" --tests "*NoShowRiskScoringIT"
--tests "*LeadScoringV2IT" --tests "*OpenApiEndpointIT"` (Docker up). `*RebookingNudge*` / salon-spa
deposit ITs matched no class — **the salon-spa module shipped with no dedicated ITs** (confirmed by
globbing `src/test`), so `RiskTieredPreventionIT` (which exercises the reused deposit path end-to-end)
is the de-facto deposit-path regression proof.

| Class | tests | failures | errors | skipped |
|---|---|---|---|---|
| `RiskTieredPreventionIT` (CF-2, new) | 5 | 0 | 0 | 0 |
| `NoShowRiskScoringIT` (CF-1 regression — UNCHANGED) | 8 | 0 | 0 | 0 |
| `LeadScoringV2IT` (lead-scorer regression — UNCHANGED) | 4 | 0 | 0 | 0 |
| `OpenApiEndpointIT` | 2 | 0 | 0 | 0 |
| **TOTAL** | **19** | **0** | **0** | **0** |

`RiskTieredPreventionIT` cases: (1) `highRisk_requiresDeposit_andSendsPersonalizedConfirmation` — HIGH
→ `depositRequired` flips, a DRAFT `Invoice` ($50 = 25% of $200) exists via the reused path, status
PENDING_DEPOSIT, one confirmation SMS carrying the personalized copy, the Claude prompt carried the
stylist (Mia) + service (Balayage), ledger `personalized=true`/`depositRequired=true`; (2)
`lowRisk_sendsSinglePersonalizedReminder_noDeposit` — LOW → no deposit, exactly one personalized
reminder, prompt carried stylist + service; (3) `claudeFailure_fallsBackToGenericReminder_noError` —
WireMock Anthropic 500 → a generic reminder still sent (mentions stylist + name), no error, ledger
`personalized=false`; (4) `nonChairfillTenant_isHardNoOp_evenWhenEventFired` — a fired event for a
non-chairfill tenant → zero deposit/SMS/ledger/Anthropic-call (defense-in-depth module re-check); (5)
`optedOutContact_getsNoSms_andReFiredEventIsIdempotent` — an `sms-opt-out` contact gets no SMS/ledger,
and a second `handle` of the same booking is a zero-duplicate no-op (ledger-insert-FIRST).

### OpenAPI
CF-2 adds **no controllers/endpoints** (it is an event subscriber + a service method), so the generated
spec is unchanged; `docs/api/openapi.json` left at HEAD; `OpenApiEndpointIT` passes (2/0/0).

### Deviations / surprises
- **No `smsOptIn` boolean exists on Contact.** Consent in the codebase is tag/subscription-based and the
  existing `SEND_SMS` dispatcher applies no opt-in gate at all. CF-2 honors a `sms-opt-out` **tag**
  (STOP) + adds a per-contact frequency cap — the strongest consent signal available without inventing a
  new Contact field (which would be a salon-core change). Flagged as the honest TCPA posture for the PoC;
  a first-class `smsConsent` field + an inbound-STOP webhook are a clean follow-up.
- **No salon-spa / rebooking deposit ITs exist on `main`** (same finding as CF-1). The CF-2 IT exercises
  the reused `requireDepositNow` deposit path (flag flip + DRAFT invoice) end-to-end, covering the
  deposit-regression concern the gate intended.
- **`requireDepositNow` placed on `SalonBookingService` (not a new helper).** The plan offered either a
  small `BookingDepositService` or "a minimal additive method `requireDepositNow(bookingId)` mirroring
  `persistWithDeposit`" — chose the latter (the deposit logic + `InvoiceRepository` already live on
  `SalonBookingService`; a separate helper would duplicate the wiring). Kept dependency-free: the CF-2
  service computes the amount (it has `ServiceMenuRepository`) and passes it in.

---

# CF-3 — ChairFill gap-fill waitlist auto-offer (the double-YES-correct showpiece) — Progress Ledger

> Spec: `~/.claude/plans/chairfill-salon-flagship.md` CF-3 section + D2 (the double-YES race).
> Branch: `chairfill-salon-flagship-phase-3-gapfill-waitlist`. Error band **4230-4239**.

## Goal
On a salon booking cancel → rank waitlisted clients for that freed slot (the CF-1 risk model **inverted**
= most-likely-to-accept-and-show first) → Claude drafts a personalized, time-boxed SMS offer → the
**first client to reply "YES" atomically claims** the slot; late repliers get an apologetic auto-reply.
The net-new inbound-SMS webhook carries the YES (and a STOP → opt-out, closing the CF-2 TCPA follow-up).

## Design as built
- **`BOOKING_CANCELLED` emit (the only salon-core change).** `SalonBookingService.cancel()` was found to
  emit NO event. Added an **additive** `.doOnNext(saved -> emitCancelled(saved))` on the save (mirroring
  `complete()`'s `emitCompleted` exactly) — the two pre-existing branches (already-CANCELLED no-op,
  COMPLETED-rejection 2902) emit nothing, byte-equivalent to before. Payload
  `{bookingId, contactId, staffMemberId, serviceMenuItemId, scheduledStart, scheduledEnd}`. Advisory,
  NMM-irrelevant (no waitlist subscriber runs for a non-chairfill tenant).
- **The ranking (inverted CF-1).** `WaitlistMatchService` filters OPEN + opted-in + slot-matching entries
  (service/stylist/time-window filters, all optional), then ranks ascending by a per-contact no-show risk
  computed by **mirroring** CF-1's deterministic rules (`scoreWithRules` polarity, NO_SHOW=1) over the
  three history-derived priors (`priorNoShowRate`, `priorBookingCount`, `daysSinceLastVisit`) — lowest
  no-show risk first = most-likely-to-show. A **mirror not a call** because CF-1's `features`/`scoreWith*`
  are private and Booking-coupled (there's no Booking to score at gap-fill time); a deterministic ranking
  is also far easier to demo/defend + test-stable than a per-run-varying model order. CF-1 left untouched.
- **The offer copy.** `OfferCopyService` is a verbatim-shape sibling of CF-2's `ReminderCopyService` (and
  `GbpReplyDraftService`): per-tenant Anthropic key + house-key fallback, `AiUsageRecorder` budget gate
  BEFORE + record AFTER, WireMock-able base-url, defensive parse, 1200/1202/1203 reused. System prompt asks
  for a warm, **time-boxed** offer (reply YES within the window). Best-effort: a Claude/budget failure →
  a deterministic generic offer (never blocks, never texts a blank).
- **The inbound-SMS webhook (NET-NEW — verified no inbound-SMS route existed on `main`; only voicemail +
  voice).** `TwilioInboundSmsController` (`POST /public/integrations/twilio/{id}/sms`, `@ConditionalOnProperty`
  chairfill-gated → absent from the OpenAPI spec) → `InboundSmsService`, a structural mirror of
  `TwilioVoicemailService.verifiedConnection`: connection lookup → authToken → `TwilioRequestValidator.verify`
  → **4000** sig-invalid / **4001** not-connected / **4003** bad-tenant-id (reused verbatim), tenant from
  the path only. Reuses `TwilioVoicemailController.reconstructFullUrl` (same package) for the proxy-aware
  signed URL. Body routing: a STOP-family word → set the CF-2 `sms-opt-out` tag (idempotent); a
  YES-family word → the atomic claim; anything else → no-op 200.
- **Correlation.** The sender `From` → the most-recent still-`OFFERED`, un-expired `WaitlistOffer` for that
  tenant + phone (`findByTenantIdAndContactPhoneAndStatusOrderBySentAtDesc`, filtered on `expiresAt`).
- **The atomic claim (D2 — the hard part).** The contended resource is "the freed slot," which no existing
  doc uniquely represents, so `WaitlistClaimService` mints a per-slot `chairfill_waitlist_claims` doc keyed
  `_id = "<tenantId>:<freedBookingId>"` and claims it with a single `ReactiveMongoTemplate.findAndModify`
  (the `WorkOrderNumberGenerator` precedent): `query = {_id: slotKey, claimedByContactId: null}`,
  `update = $set{claimedByContactId, claimedOfferId, claimedAt}`, `opts = returnNew(true).upsert(true)`.
  Exactly one concurrent YES finds the doc unclaimed and writes it (**winner** — gets the doc back, claimed
  by itself); every other YES's `claimedByContactId:null` predicate no longer matches → `upsert` attempts a
  second insert with the same unique `_id` → `DuplicateKeyException` → **loser**. First-writer-wins,
  atomically, at the DB. Winner → a real Booking via the **unchanged** `SalonBookingService.create` (so
  `BookingPolicyService.validate` guards against double-book; `Booking @Version` is the further backstop),
  offer→CLAIMED, siblings→SUPERSEDED, entry→FULFILLED, confirmation SMS, `WAITLIST_SLOT_CLAIMED` emit.
  Loser → apologetic auto-reply, offer→SUPERSEDED, no booking, no error.
- **STOP handling.** A STOP inbound sets the CF-2 `sms-opt-out` tag on every contact matching the `From`
  number — **closes the CF-2 deviation** (CF-2 noted "an inbound-STOP webhook [is] a clean follow-up").
- **The waitlist-join widget.** `WaitlistWidgetController` (`POST /public/widget/salon-waitlist/{token}`,
  chairfill-gated) is a clone of `SalonBookingWidgetController`: HMAC token verify → assert
  `widgetType="salon-waitlist"` (mismatch → **4230**) → synthetic `PUBLIC_WIDGET` context → upsert Contact
  by email → create an OPEN `WaitlistEntry` (`smsOptIn=true` — the join IS the consent act).

## Invariants
- **`cancel()` byte-equivalent except the additive emit (HARD GATE 1):** only `.doOnNext(emitCancelled)`
  added on the save path; both other branches unchanged; salon-core + CF-1 + CF-2 + lead-scorer + voicemail
  ITs stay green.
- **Concurrency-correct claim (HARD GATE 2):** the slot-level `findAndModify` is the primary gate; the
  `WaitlistOffer`/`Booking` `@Version` is the backstop; `BookingPolicyService.validate` prevents double-book.
  The IT subscribes two `handleAffirmative` Monos in parallel and asserts exactly one WON / one booking /
  one CLAIMED / one apology.
- **Best-effort (HARD GATE 3):** every external call (Claude, SMS) and the winner's booking-create are
  wrapped — a failure degrades (generic copy / skip-that-offer / apologize-instead) and never corrupts the
  cancel or the waitlist.
- **Module-gated, blast-radius zero (HARD GATE 4):** all CF-3 beans are `@ConditionalOnProperty(chairfill)`
  + `@ConditionalOnBean(SalonBookingService.class)`; controllers `@ConditionalOnProperty`-gated; `GapFillService`
  additionally re-checks `Tenant.enabledModules` (defense-in-depth) so a `BOOKING_CANCELLED` from a
  non-chairfill salon tenant is a hard no-op.
- **Inbound-SMS signature-verified (HARD GATE 5):** reuses `TwilioRequestValidator` + 4000-4003. Error band
  4230-4239 (only 4230 minted; the rest reserved).
- **No new error codes beyond 4230:** the inbound-SMS webhook reuses 4000-4003; AI reuses 1200-1203; Twilio
  SMS reuses 2530-2532; booking/policy reuses 2900-2901.

## Files
**Created**
- `module/salonspa/.../` — none (only the additive emit in `SalonBookingService`).
- `module/chairfill/model/WaitlistEntry.java` + `WaitlistEntryRepository.java` — the join pool (OPEN/FULFILLED/CANCELLED, opt-in, optional filters).
- `module/chairfill/model/WaitlistOffer.java` + `WaitlistOfferRepository.java` — the time-boxed offer ledger (`@Version`, OFFERED/CLAIMED/SUPERSEDED/EXPIRED), inbound-YES correlation finder.
- `module/chairfill/widget/WaitlistWidgetController.java` + `WaitlistJoinSubmissionDTO.java` + `WaitlistJoinResponseDTO.java` — the `salon-waitlist` public join widget (4230 on type mismatch).
- `module/chairfill/gapfill/WaitlistMatchService.java` — the inverted-CF-1 ranking.
- `module/chairfill/ai/OfferCopyService.java` — the Claude time-boxed offer drafter (ReminderCopyService sibling).
- `module/chairfill/gapfill/GapFillService.java` — the `@PostConstruct` subscriber on `BOOKING_CANCELLED` (rank → offer → send top-N).
- `module/chairfill/gapfill/WaitlistClaimService.java` — the atomic `findAndModify` slot claim (winner→booking, loser→apology).
- `integration/twilio/InboundSmsService.java` — the net-new inbound-SMS handler (signature-verify + YES/STOP routing).
- `controller/integration/TwilioInboundSmsController.java` — `POST /public/integrations/twilio/{id}/sms` (chairfill-gated).
- `src/test/.../module/chairfill/GapFillWaitlistIT.java` — the CF-3 IT (11 cases incl. concurrent double-YES).

**Modified**
- `module/salonspa/service/SalonBookingService.java` — additive `BOOKING_CANCELLED` emit in `cancel()` (the only salon-core change).
- `automation/DomainEventType.java` — `BOOKING_CANCELLED`, `WAITLIST_OFFER_SENT`, `WAITLIST_SLOT_CLAIMED`.
- `module/chairfill/ChairFillAutoConfiguration.java` — register the 5 CF-3 beans (all `@ConditionalOnBean(SalonBookingService.class)`).
- `controller/advice/GlobalErrorHandler.java` — the 4230-4239 band doc-comment.

## Sub-steps
1. `BOOKING_CANCELLED` additive emit + 3 new event constants — done.
2. `WaitlistEntry`/`WaitlistOffer` models + repos + the `salon-waitlist` join widget — done.
3. `WaitlistMatchService` (inverted ranking) + `OfferCopyService` (Claude) + `GapFillService` subscriber — done.
4. `WaitlistClaimService` (atomic `findAndModify`) + `InboundSmsService`/`TwilioInboundSmsController` (signature-verified YES/STOP) — done.
5. Beans wired + 4230-4239 doc band — done.
6. `GapFillWaitlistIT` (11 cases) + regression gates — done, all green.

## Test result — BUILD SUCCESSFUL

`./gradlew cleanTest test --tests "*GapFillWaitlistIT" --tests "*RiskTieredPreventionIT"
--tests "*NoShowRiskScoringIT" --tests "*TwilioVoicemailIT" --tests "*LeadScoringV2IT"
--tests "*OpenApiEndpointIT" --tests "*GbpReplyDraftServiceIT"` (Docker up). `*Salon*`/`*Booking*`/
`*Rebook*` matched no dedicated salon-core IT (the salon-spa module shipped with none — confirmed by
globbing `src/test`); the CF-3 `cancel_emitsBookingCancelled` case exercises the only salon-core change
end-to-end (asserts the CANCELLED transition AND the additive emit).

| Class | tests | failures | errors | skipped |
|---|---|---|---|---|
| `GapFillWaitlistIT` (CF-3, new) | 11 | 0 | 0 | 0 |
| `RiskTieredPreventionIT` (CF-2 regression — UNCHANGED) | 5 | 0 | 0 | 0 |
| `NoShowRiskScoringIT` (CF-1 regression — UNCHANGED) | 8 | 0 | 0 | 0 |
| `TwilioVoicemailIT` (inbound Twilio webhook regression — UNCHANGED) | 6 | 0 | 0 | 0 |
| `LeadScoringV2IT` (lead-scorer regression — UNCHANGED) | 4 | 0 | 0 | 0 |
| `GbpReplyDraftServiceIT` (AI-services regression — UNCHANGED) | 4 | 0 | 0 | 0 |
| `OpenApiEndpointIT` | 2 | 0 | 0 | 0 |
| **TOTAL** | **40** | **0** | **0** | **0** |

`GapFillWaitlistIT` cases: (1) `waitlistJoinWidget_createsEntry` — token-gated widget creates an OPEN
opted-in `WaitlistEntry`; (2) `waitlistJoinWidget_wrongWidgetType_4230` — a `salon-booking`-typed token →
4230, no entry; (3) `cancel_emitsBookingCancelled` — `cancel()` flips CANCELLED AND emits `BOOKING_CANCELLED`
with the freed-slot payload; (4) `gapFill_ranksReliableAboveFlaky_andSendsPersonalizedOffer` — a reliable
regular (no prior NO_SHOW) ranks rank-0 ABOVE a flaky client (a prior NO_SHOW) rank-1, both texted, the
Claude prompt carried the stylist; (5) `gapFill_claudeFailure_fallsBackToGenericOffer_noError` — WireMock
Anthropic 500 → a generic time-boxed offer still sent (mentions stylist + YES), no error; (6)
`inboundYes_claimsSlot_createsBookingViaNormalPath` — an inbound YES over the signed webhook → a real
CONFIRMED Booking via `create()`, offer CLAIMED, one confirmation SMS; (7)
**`concurrentDoubleYes_exactlyOneBooking_oneApology`** — two parallel YESs for one slot → exactly one WON /
one LOST / **one Booking** / one CLAIMED / one SUPERSEDED / one apologetic SMS (the crown jewel); (8)
`expiredOffer_cannotBeClaimed_noBooking` — a past-`expiresAt` offer → NO_OPEN_OFFER, no booking, no SMS; (9)
`inboundStop_setsOptOutTag` — an inbound STOP → the contact gets the `sms-opt-out` tag (CF-2 follow-up
closed); (10) `nonChairfillTenant_gapFillIsHardNoOp` — a `BOOKING_CANCELLED` for a non-chairfill tenant →
zero offers/SMS/Anthropic-call (defense-in-depth); (11) `inboundSms_badSignature_401_4000_zeroEffect` — a
bad `X-Twilio-Signature` → 401/4000, the offer untouched, no booking, no SMS.

### OpenAPI
CF-3's two public controllers (`WaitlistWidgetController`, `TwilioInboundSmsController`) are BOTH
`@ConditionalOnProperty(kmosf.modules.chairfill.enabled)`-gated, so they are absent from the generated spec
when the module is off (the `NoShowRiskController` / HS precedent — and `OpenApiEndpointIT` runs without
`chairfill.enabled=true`). `docs/api/openapi.json` left at HEAD; `OpenApiEndpointIT` passes (2/0/0).

### Deviations / surprises
- **Inbound-SMS route was net-new (the plan's flagged scope risk, confirmed).** No inbound-SMS webhook
  existed on `main` — only the voicemail transcription callback + the voice TwiML webhook. Built it
  signature-verified mirroring `TwilioVoicemailService`/`TwilioRequestValidator`, reusing 4000-4003. No
  `MessageSid` idempotency ledger (unlike voicemail's `CallSid` ledger): a re-delivered YES is naturally
  idempotent (the slot is already CLAIMED → a re-claim is a loser/apology, never a 2nd booking — the atomic
  guard handles it), and a re-delivered STOP is an idempotent tag-set — the `MoleTriageController`
  "intentionally re-invocable public surface" posture.
- **Ranking is a mirror of CF-1, not a call into it.** `NoShowRiskScoringService.features`/`scoreWithRules`
  are private + coupled to scoring a specific upcoming Booking. `WaitlistMatchService` re-implements the
  same deterministic rules over the history-only priors (the only ones knowable with no Booking to score),
  ranking ascending. CF-1 stays untouched; the ranking is deterministic (test-stable, demo-defensible).
- **The contended resource ("the slot") gets its own claim doc.** Per D2, neither the `WaitlistOffer` nor
  the `Booking` uniquely represents "the freed slot" before a winner exists, so a dedicated per-slot
  `chairfill_waitlist_claims` doc (keyed `_id="<tenant>:<freedBookingId>"`, accessed via `ReactiveMongoTemplate`,
  NOT a repo entity — the `WorkOrderNumberGenerator` pattern) is the `findAndModify` target. `upsert(true)` +
  the unique `_id` makes the first-ever-claim insert race resolve to one winner + DuplicateKeyException for
  the rest.
- **Test-precision nit (not a product bug).** Mongo persists `Instant` at millisecond precision; the seeded
  `slotStart` carried nanos, so the winner-booking's `scheduledStart` asserted with a 1ms tolerance
  (`isCloseTo`) rather than exact equality — the booking window is correct to the ms.
- **STOP closes the CF-2 TCPA follow-up.** CF-2's ledger noted "an inbound-STOP webhook [is] a clean
  follow-up"; CF-3's inbound-SMS route now sets the `sms-opt-out` tag on STOP, so the CF-2 consent gate is
  honored end-to-end.

---

# CF-4 — ChairFill AI review-reply, salon-generalized + RAG voice + the reused approval queue — Progress Ledger

> Off `main` @ `28e6bdf` (CF-1/CF-2/CF-3 merged). Branch
> `chairfill-salon-flagship-phase-4-review-reply`. Spec: `~/.claude/plans/chairfill-salon-flagship.md`
> CF-4 + decision D4. The last BE sub-phase of ChairFill (CF-5 is the FE pass, separate repo).

## Goal

Bring the shipped GBP review-reply capability to salons: a customer review -> a Claude-drafted,
**on-brand salon-voiced** reply (RAG-grounded in the salon's own past approved replies) -> the
**reused staff approval queue** (draft -> approve/skip; approve posts via GBP when wired, else
copy-ready). A **paste-in** entry is the demo path (no live Google OAuth). **NMM's GBP review-reply
flow stays byte-equivalent**; never auto-post; module-gated; best-effort drafting.

## Design (D4 — generalize, don't fork the transport)

- **`GbpReplyDraftService` (the shipped transport) generalized additively.** Added an overload
  `draftReply(GbpReview, String systemPromptOverride, List<ReplyExemplar> exemplars)`; the original
  single-arg `draftReply(review)` now delegates with `(review, null, null)`. `systemPrompt(null)`
  returns the exact configured/default GBP prompt; `buildUserPrompt(review, null)` is the verbatim
  original user message (the exemplar block is fully guarded by `exemplars != null && !isEmpty()`).
  **NMM/GBP is byte-equivalent by construction** — `GbpReplyDraftServiceIT` passes UNCHANGED, plus a
  dedicated CF-4 byte-equivalence test asserts the single-arg call sends the GBP default prompt and
  NO exemplar block. New nested `record ReplyExemplar(Integer rating, String reviewText, String
  approvedReply)`.
- **`SalonReviewReplyService`** (module-gated `@Bean`, sibling posture) is the salon brain: builds a
  salon brand-tone system prompt (a warm stylist/salon base + the per-tenant
  `kmosf.chairfill.review-system-prompt` hint), retrieves a few RAG exemplars (best-effort), calls
  the unchanged transport's overload, and parks the draft **DRAFTED in the same `GbpReviewReply`
  queue NMM uses**. Ledger-insert-FIRST (the unique `tenant_review_idx` exactly-once backstop);
  idempotent on the review id (a paste-in with no id mints a synthetic `pasted/<uuid>`). Best-effort:
  a Claude failure (1200/1202/1203) -> a sentiment-aware **generic on-brand fallback draft** (never
  blank, never thrown, never a dropped review). **Never calls `postReply`** — posting is staff-only
  on the reused admin surface.
- **RAG over past approved replies** via a small `ReplyExemplarSource` seam. The shipped default
  `LedgerReplyExemplarSource` retrieves the tenant's own **POSTED** `GbpReviewReply` rows (the literal
  corpus of approved replies), ranked by rating proximity to the new review (a 1-star reply best
  models tone for another 1-star), capped via `take(25)` then `limit`. Best-effort by contract (a
  query failure -> empty list). New finder `findByTenantIdAndStatusOrderByPostedAtDesc`.
- **Paste-in surface** `SalonReviewReplyController` (`POST /chairfill/reviews/draft`): STAFF-gated
  (`RoleGuard`) + module-gated (`TenantModuleRegistry.requireEnabled` + `@ConditionalOnProperty`),
  `4240` on a blank review text, returns the queued `GbpReviewReply`. The **reused**
  `GbpReviewReplyAdminController` (`GET /gbp/review-replies`, `POST /{id}/post`|`/{id}/skip`) lists,
  approves, or skips it — unchanged.

## Files

**Modified (4):**
- `integration/gbp/GbpReplyDraftService.java` — additive overload + `ReplyExemplar` record;
  byte-equivalent single-arg delegation.
- `repository/gbp/GbpReviewReplyRepository.java` — `findByTenantIdAndStatusOrderByPostedAtDesc`
  (the exemplar corpus finder).
- `module/chairfill/ChairFillAutoConfiguration.java` — CF-4 `@Bean`s (`ReplyExemplarSource`,
  `SalonReviewReplyService`) + class-javadoc CF-4 bullet.
- `controller/advice/GlobalErrorHandler.java` — the `4240-4244` band doc (one minted code: `4240`).

**Created (5):**
- `module/chairfill/reviews/ReplyExemplarSource.java` — the pluggable RAG seam (best-effort contract).
- `module/chairfill/reviews/LedgerReplyExemplarSource.java` — the shipped ledger-backed default.
- `module/chairfill/reviews/SalonReviewReplyService.java` — the drafter + queue brain.
- `module/chairfill/controller/SalonReviewReplyController.java` — the paste-in endpoint.
- `src/test/.../module/chairfill/SalonReviewReplyIT.java` — the CF-4 IT (8 tests).

## Validation

`./gradlew compileJava compileTestJava` clean. Then force-clean:
`./gradlew cleanTest test --tests "*SalonReviewReplyIT" --tests "*GbpReplyDraftServiceIT" --tests
"*GbpReviewReplyAdminIT" --tests "*GbpReviewPollerIT" --tests "*GapFillWaitlistIT" --tests
"*RiskTieredPreventionIT" --tests "*NoShowRiskScoringIT" --tests "*OpenApiEndpointIT"` ->
**BUILD SUCCESSFUL**, per-class tests/failures/errors: SalonReviewReplyIT 8/0/0,
GbpReplyDraftServiceIT 4/0/0, GbpReviewReplyAdminIT 6/0/0, GbpReviewPollerIT 2/0/0,
GapFillWaitlistIT 11/0/0, RiskTieredPreventionIT 5/0/0, NoShowRiskScoringIT 8/0/0,
OpenApiEndpointIT 2/0/0 — **46/0/0**.

CF-4 IT cases: (1) `pasteIn_draftsSalonVoicedReply_withBrandTonePromptAndExemplar` — a salon paste-in
-> DRAFTED in the queue, the Claude request carrying the **salon brand-tone prompt** + a **RAG
exemplar** past approved reply (asserted via `matchingJsonPath`); (2)
`approve_postsReply_andLeavesQueue` — approve via the reused admin endpoint -> POSTED, PUT to GBP,
leaves the DRAFTED queue; (3) `skip_marksSkipped_andLeavesQueue` — skip -> SKIPPED, no Google call,
leaves the queue; (4) `claudeFailure_fallsBackToGenericDraft_noError` — a WireMock 500 -> a non-blank
**generic on-brand** draft (no error, still DRAFTED); (5) `nonChairfillTenant_pasteIn_isModuleGated_1132`
— a tenant without `chairfill` -> 1132, no row, no Claude call; (6) `pasteIn_blankComment_4240`;
(7) `pasteIn_nonStaff_isForbidden_1800`; (8) **`nmmByteEquivalence_singleArgDraft_usesGbpDefaultPrompt_noExemplars`**
— the unchanged single-arg `draftReply(review)` sends the GBP default prompt + NO exemplar block (the
NMM regression guard, alongside the unchanged GBP ITs).

## OpenAPI

`SalonReviewReplyController` is `@ConditionalOnProperty(kmosf.modules.chairfill.enabled)`-gated, so it
is absent from the generated spec when the module is off (the `NoShowRiskController`/`WaitlistWidgetController`
precedent — `OpenApiEndpointIT` runs without `chairfill.enabled=true`). Confirmed `docs/api/openapi.json`
contains no `chairfill/reviews` path and is left at HEAD; `OpenApiEndpointIT` passes (2/0/0).

## Deviations / surprises

- **RAG source is the POSTED-reply ledger, not the Atlas vector spine (`RagRetrievalService`).** The
  plan D4 suggested grounding exemplars "via the shipped `RagRetrievalService`/`AskAiService` spine."
  That path requires a live OpenAI embedding call + a Mongo **Atlas** Vector Search index — neither
  exists in CI / a fresh cluster (it falls back to empty there), so it would always yield zero
  exemplars in the demo + tests. The shipped default instead retrieves the tenant's own **POSTED**
  `GbpReviewReply` rows — which IS the literal "salon's past approved replies" corpus D4 names — and
  is robust everywhere. The `ReplyExemplarSource` interface is the seam: a future vector-backed source
  is a drop-in `@Bean` override with zero change to the drafter, the queue, or NMM. This keeps the
  hard gate ("RAG over the salon's past approved replies") satisfied more directly while staying
  testable + demo-defensible. (The plan itself hedges: "defensive/best-effort; a RAG/Claude failure ->
  a sensible generic draft, never blocks.")
- **No new ledger model.** CF-4 reuses `GbpReviewReply` (the ledger doubles as the approval queue), so
  paste-in drafts and GBP-polled drafts share one queue + one admin surface. A paste-in with no
  caller id gets a synthetic `pasted/<uuid>` reviewId so each manual paste is a distinct queue row
  (and re-submitting the same id is idempotent — returns the existing draft).
- **Approve->post reuses the GBP path verbatim.** A salon with a `google-business` connection
  approves->posts through the unchanged `GbpReviewReplyAdminService.post` (PUT to GBP); one WITHOUT a
  connection edits the on-brand draft and copies it into the GBP console (copy-ready) — the de-risked
  paste-in posture. No second posting path was added.
- **Low-severity benign race (documented, not fixed).** Two simultaneous paste-ins of the *same*
  review id could both reload the row before either drafts, attempting two Claude calls (last save
  wins, one queue row — no duplicate). Acceptable for a low-concurrency staff paste-in PoC; not worth
  a lock.

# CF-5a — ChairFill waitlist-board read API (backing the CF-5 board FE) — Progress Ledger

## Goal

A small, additive, staff-facing **read** endpoint so the CF-5 waitlist board FE can show the salon's
current gap-fill state: the OPEN `WaitlistEntry` rows (clients waiting) + recent `WaitlistOffer` rows
(who's been offered what, with status OFFERED/CLAIMED/SUPERSEDED/EXPIRED), newest first. CF-3 mints
these rows but exposed no admin read; this fills that gap. Purely additive — CF-1..CF-4 behavior
unchanged.

## Design as built (mirror of `NoShowRiskController` + `SalonReviewReplyController`)

- **`WaitlistBoardController`** (`module/chairfill/controller/`), `@RequestMapping("/chairfill/waitlist")`,
  `@ConditionalOnProperty(prefix="kmosf.modules.chairfill", name="enabled")`. Three GETs (base-path
  `/api/v1`):
  - `GET /chairfill/waitlist/board` — the one-shot envelope `WaitlistBoardDTO { openEntries[], recentOffers[] }`
    (the FE's primary call); `?offerLimit=` caps the recent-offers slice (default 50).
  - `GET /chairfill/waitlist/entries` — just the OPEN entries (`WaitlistBoardEntryDTO[]`), newest join first.
  - `GET /chairfill/waitlist/offers?limit=` — just the recent offers (`WaitlistOfferDTO[]`, all statuses),
    newest sent first, capped (default 50).
- **Gate** (per handler, via a shared `guard()`): `TenantModuleRegistry.requireEnabled("chairfill")`
  (1130/1132 module gate — the 4220/4202/2700/3930 not-enabled posture) **then**
  `RoleGuard.requireRole("STAFF")` (1800) — the CF-4 order.
- **Lean DTOs** (`controller/dto/`): flat projections of the entity fields (the `MissedCallInboxItemDTO`
  precedent — **no cross-collection join**). `WaitlistBoardEntryDTO` surfaces `contactId` /
  `preferredStaffMemberId` / service-filter / time window / smsOptIn / notes / createdAt;
  `WaitlistOfferDTO` surfaces the offer fields incl. the already-denormalized `contactPhone` +
  `serviceMenuItemName` and the `status` enum. The FE resolves the ids against contacts/staff it
  already loads.
- **Additive repo finders:** `WaitlistEntryRepository.findByTenantIdAndStatusOrderByCreatedAtDesc`
  (the board loads OPEN, newest first; the gap-fill keeps the existing unordered finder) +
  `WaitlistOfferRepository.findByTenantIdOrderBySentAtDesc` (recent offers; controller `.take(cap)`).

## Files

- **NEW** `module/chairfill/controller/WaitlistBoardController.java`
- **NEW** `module/chairfill/controller/dto/WaitlistBoardDTO.java`
- **NEW** `module/chairfill/controller/dto/WaitlistBoardEntryDTO.java`
- **NEW** `module/chairfill/controller/dto/WaitlistOfferDTO.java`
- **NEW** `src/test/.../module/chairfill/WaitlistBoardIT.java`
- **MOD** `module/chairfill/model/WaitlistEntryRepository.java` (+1 ordered finder)
- **MOD** `module/chairfill/model/WaitlistOfferRepository.java` (+1 ordered finder)
- **MOD** `controller/advice/GlobalErrorHandler.java` (CF-5a `4245-4249` reserved-band doc entry)

## Error codes

**CF-5a mints NO new error code.** The read reuses the shared `TenantModuleRegistry.requireEnabled`
module gate (1130/1132) and the `RoleGuard` STAFF gate (1800). The band **`4245-4249`** is RESERVED
(documented in `GlobalErrorHandler`) for future board-read growth.

## Validation — BUILD SUCCESSFUL

`./gradlew compileJava compileTestJava` → clean. Then force-clean targeted suite:
`./gradlew cleanTest test --tests "*WaitlistBoard*IT" --tests "*GapFillWaitlistIT" --tests
"*SalonReviewReplyIT" --tests "*NoShowRiskScoringIT" --tests "*OpenApiEndpointIT"` → **BUILD
SUCCESSFUL**. Per-class (from `build/test-results/test/*.xml`):

- `WaitlistBoardIT` — tests=6, failures=0, errors=0 (NEW)
- `GapFillWaitlistIT` (CF-3) — tests=11, failures=0, errors=0
- `SalonReviewReplyIT` (CF-4) — tests=8, failures=0, errors=0
- `NoShowRiskScoringIT` (CF-1) — tests=8, failures=0, errors=0
- `OpenApiEndpointIT` — tests=2, failures=0, errors=0

`WaitlistBoardIT` coverage: (1) `board` returns OPEN entries + recent offers with correct projection,
OPEN-only filtering (FULFILLED excluded), newest-first ordering, offer status surfaced;
(2) `entries` OPEN-only newest-first (CANCELLED excluded); (3) `offers` all statuses, newest-sent-first,
`limit` cap honored; (4) `nonChairfillTenant_board_isModuleGated_1132`; (5) `nonStaff_board_isForbidden_1800`;
(6) `board_isTenantIsolated` — another tenant's rows never leak.

## OpenAPI

`WaitlistBoardController` is `@ConditionalOnProperty(kmosf.modules.chairfill.enabled)`-gated, so it is
absent from the generated spec when the module is off (the `NoShowRiskController`/`SalonReviewReplyController`
precedent — `OpenApiEndpointIT` runs without the chairfill flag). Confirmed `docs/api/openapi.json`
contains no `chairfill/waitlist` path and is left byte-unchanged at HEAD; `OpenApiEndpointIT` passes (2/0/0).

## Deviations / surprises

- **None material.** Kept DTOs as flat projections (no contact/stylist name enrichment join) per the
  `MissedCallInboxItemDTO` lean-projection precedent and the "small + additive" mandate — the offer
  already denormalizes `contactPhone` + `serviceMenuItemName`, and the FE resolves ids against the
  contacts/staff it loads for the board. A reactive per-row name-join would have been the wrong altitude.
- **`@CreatedDate` seeding gotcha (handled):** `WaitlistEntry.createdAt` is `@CreatedDate`, so the IT
  seeds via save-then-`toBuilder().createdAt(...)`-resave (the `RetentionPurgeIT` back-dating precedent)
  to make the newest-first ordering assertions deterministic. `WaitlistOffer` ordering keys on `sentAt`
  (a plain field), so it needs no such dance.
