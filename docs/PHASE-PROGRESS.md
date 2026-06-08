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
