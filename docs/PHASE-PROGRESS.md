# FD-1 — FrontDesk IQ: module + PHI-free Appointment + no-show-risk model — Progress Ledger

> Crash-recovery source of truth for `frontdesk-iq-phase-1-noshow-risk`
> (off `main` @ `2bbaa74`). Fresh ledger for this change (supersedes the prior RE-5a ledger that
> occupied this path — that work is already on `main`).
>
> Spec: `~/.claude/plans/frontdesk-iq-flagship.md` §0 (the PHI boundary by construction), §1 (key
> decisions D1–D4), §2 (FD-1 sub-phase), §3 (FD-1 detailed spec — the feature vector + rules ladder).
> Error band: **4275-4279** (FD band 4275-4299; RE consumed 4250-4274).

## Goal (FD-1)

A nightly, per-tenant, **PHI-free** no-show-risk score on UPCOMING health `Appointment`s, mirroring CF-1
**without disturbing CF-1, the lead-scorer, or the core calendar.** The headline is the PHI boundary,
enforced *by construction*: the `Appointment` model + the scorer's feature vector carry **no clinical
field**, so the model literally cannot see a diagnosis (fence F1). A release-blocking IT asserts it.

## Design as built

### The PHI-free `Appointment` (fence F1) — the headline
- `module/frontdesk/model/Appointment.java` — a thin, module-gated `@Document("frontdesk_appointments")`
  modeled on the *non-salon* parts of salon `Booking` + the logistics signals the model needs. Fields are
  **logistics metadata ONLY**: `contactId`, opaque `providerId` (never rendered outbound — F3),
  `scheduledStart`/`scheduledEnd`, `status` (`AppointmentStatus`), `visitTypeBucket` (`VisitTypeBucket`),
  `insuranceVerificationPending` (boolean), `lastVisitAt` (recall metadata timestamp), `reminderCount`,
  embedded `noShowRisk`, sparse `calComBookingUid`, version/timestamps. **No `diagnosis`/`procedure`/
  `chiefComplaint`/`clinicalNotes` field exists** — that absence is the provable boundary. (D4
  "refuse the field, don't flag the field".)
- `AppointmentStatus` = `SCHEDULED, CONFIRMED, COMPLETED, NO_SHOW, CANCELLED` (pure lifecycle, no clinical
  meaning). `VisitTypeBucket` = closed enum `NEW_PATIENT, RECALL, FOLLOW_UP, HYGIENE, ANNUAL_WELLNESS,
  OTHER` + lenient `fromWire` (unknown → OTHER; closed enum = staff cannot encode free-text PHI). This is
  the line FD rides up to and fences (a scheduling category, NOT a diagnosis; consumed only as an ordinal).

### The scorer fork (FD-1 D2 — parallel fork, NOT a generalization)
- `module/frontdesk/scoring/FrontDeskNoShowScoringService.java` — a line-shape-for-line-shape parallel
  fork of `chairfill/scoring/NoShowRiskScoringService` (itself a fork of `LeadScoringV2Service`). Same
  machine: nightly `@Scheduled` per-tenant (`enabledModules ∋ "frontdesk"` filter), Smile
  `LogisticRegression`, `>= MIN_APPTS_FOR_MODEL (40)`-sample model-vs-rules gate, `FrontDeskScoringJob`
  ledger, `boundedElastic` for the blocking ML, `APPOINTMENT_RISK_SCORED` emit per scored upcoming appt.
  **Reads only `AppointmentRepository`, never `BookingRepository`** — the two scorers share the
  `NoShowRisk` *type* but never share data. **CF-1 is untouched.**
- **The logistics-only feature vector (F1), 9 features:** `[0] priorNoShowRate, [1] priorAppointmentCount,
  [2] leadTimeHours, [3] dayOfWeek, [4] hourOfDay, [5] visitTypeBucket.ordinal(),
  [6] insuranceVerificationPending, [7] reminderCount, [8] daysSinceLastVisit`. No price-band/ServiceMenu
  lookup (health has no price band); no clinical avenue (no such field to read). Label polarity NO_SHOW=1 /
  COMPLETED=0, CANCELLED excluded.
- **Cold-start rules ladder (hard gate 3, adapted from CF-1, no deposit concept):** HIGH if
  `priorNoShowRate >= 0.34` OR (`leadTimeHours > 336` long-lead AND `reminderCount == 0` unconfirmed) →
  the deposit signal is replaced by the no-reminder-yet signal; INSUFFICIENT_DATA→LOW for a true
  first-timer (no priors, no prior visit) so a new patient is never over-flagged; MEDIUM if
  `priorNoShowRate > 0` OR a real lapsed patient (`priorAppointmentCount > 0 && daysSinceLastVisit > 90`);
  LOW otherwise. Thresholds reuse CF-1's `NoShowRisk.DEFAULT_{HIGH,MEDIUM}_THRESHOLD` (0.6/0.35),
  overridable via `kmosf.frontdesk.noshow-scoring.{high,medium}-threshold`.

### NoShowRisk value-type reuse decision + the ArchUnit finding
- **Decision: REUSE `chairfill.model.NoShowRisk` + `NoShowRiskTier` verbatim (import, not copy)** — the
  plan's preferred option. They are domain-neutral `{riskScore, riskTier, source, computedAt}` value types.
- **ArchUnit finding:** the only two arch tests are `ArchUnitVectorTenancyTest` (VectorIndex/EmbeddingService
  must take tenantId first) and `BannedThreadLocalArchTest` (no `ThreadLocal`). **Neither forbids
  module→module imports**, and `RealEstateAutoConfiguration` *already* imports
  `chairfill.ChairFillAutoConfiguration` in production — so a `frontdesk → chairfill.model` import is
  clean and precedented. No frontdesk-local fork was needed. Both arch tests pass with the import in place.

### Module gate (FD-1 D3 — the RealEstate template, no salon/HS dependency)
- `module/frontdesk/FrontDeskAutoConfiguration.java` — `@AutoConfiguration(after=RealEstateAutoConfiguration)`
  + `@ConditionalOnProperty(kmosf.modules.frontdesk.enabled)` (default OFF), hand-constructed beans
  (`@Value` lands on factory params), `ModuleDefinition("frontdesk","FrontDesk IQ","0.1.0",
  ["APPOINTMENT","NO_SHOW_RISK"])`. Rides the core CRM spine directly — **no salon-spa / home-services /
  realestate dependency** (unlike ChairFill which `@ConditionalOnBean(SalonBookingService)`-rode salon-spa).
- Controllers `@RestController` + `@ConditionalOnProperty`-gated (absent from the OpenAPI spec when off) +
  `TenantModuleRegistry.requireEnabled("frontdesk")` per handler.

## Files

### New (module)
- `module/frontdesk/FrontDeskAutoConfiguration.java`
- `module/frontdesk/model/Appointment.java` (the PHI-free document, fence F1)
- `module/frontdesk/model/AppointmentStatus.java`
- `module/frontdesk/model/VisitTypeBucket.java` (closed enum + `fromWire`)
- `module/frontdesk/model/AppointmentRepository.java`
- `module/frontdesk/model/FrontDeskScoringJob.java`
- `repository/FrontDeskScoringJobRepository.java`
- `module/frontdesk/scoring/FrontDeskNoShowScoringService.java` (the scorer fork)
- `module/frontdesk/service/AppointmentService.java` (CRUD; 4276 not-found, 4277 invalid)
- `module/frontdesk/controller/AppointmentController.java` (staff CRUD, gated + STAFF)
- `module/frontdesk/controller/NoShowRiskController.java` (retrain 202 + risk-sorted read)

### Touched (additive only)
- `automation/DomainEventType.java` (+`APPOINTMENT_RISK_SCORED = "appointment.riskScored"`)
- `controller/advice/GlobalErrorHandler.java` (+4275-4279 doc band)
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (+FrontDeskAutoConfiguration)

### Reused, NOT copied
- `module/chairfill/model/NoShowRisk.java`, `NoShowRiskTier.java` (imported verbatim).

### Tests
- `src/test/java/.../module/frontdesk/FrontDeskNoShowScoringServiceIT.java` — (1) ≥40 terminal → MODEL;
  (2) below-threshold → RULES_FALLBACK (cold start); (3) cold-start ladder (long-lead-unconfirmed → HIGH,
  first-timer → INSUFFICIENT_DATA/LOW); (4) determinism across runs; (5) `APPOINTMENT_RISK_SCORED` payload;
  (6) only-upcoming-scored, terminal untouched; (7) blast radius — non-frontdesk tenant skipped by
  `nightlyRun`; **(8) the PHI fence — reflection assertion that `Appointment` has no clinically-named
  field; (9) the feature vector is logistics-only + fixed-width (9)**; (10) lead-scoring untouched.
- `src/test/java/.../module/frontdesk/NoShowRiskControllerIT.java` — (1) retrain 202 + job; (2) risk-sorted
  day view (highest first); (3) CRUD round-trip; (4) invalid create → 4277; (5) missing appt → 4276;
  (6) non-frontdesk tenant → 1132; (7) non-staff → 1800. (`WebTestClient` + JWT, the RE-5a pattern.)

## Validation status

- `./gradlew compileJava compileTestJava` — GREEN.
- `./gradlew cleanTest test --tests "*FrontDesk*IT" --tests "*frontdesk.NoShowRiskControllerIT"
  --tests "*NoShowRiskScoringIT" --tests "*LeadScoringV2IT" --tests "*OpenApiEndpointIT" --tests "*Arch*"`
  — **GREEN** (force-clean). Per-class (tests/failures/errors):
  FrontDeskNoShowScoringServiceIT 10/0/0; NoShowRiskControllerIT 7/0/0; NoShowRiskScoringIT (CF-1) 8/0/0;
  LeadScoringV2IT 4/0/0; OpenApiEndpointIT 2/0/0; ArchUnitVectorTenancyTest 2/0/0; BannedThreadLocalArchTest
  1/0/0. (34 total, 0 failures.)

## Hard gates

1. **PHI-free by construction (F1)** — `Appointment` + the 9-feature vector carry no clinical data; the
   release-blocking `appointmentModelHasNoClinicalField` reflection IT + the fixed-width feature IT pass.
2. **CF-1 `NoShowRiskScoringService` + the shipped `LeadScoringV2Service` untouched** — `NoShowRiskScoringIT`
   (8/0/0) + `LeadScoringV2IT` (4/0/0) pass unchanged. FrontDesk reads only `AppointmentRepository`.
3. **Cold-start** rules fallback mirrors CF-1 (verified by the below-threshold + ladder ITs) — a new
   practice gets value night one.
4. **Module-gated, blast radius zero** — `@ConditionalOnProperty` default OFF; the nightly run skips
   non-frontdesk tenants (`moduleDisabled_skipsTenant` IT). Controllers absent from the OpenAPI spec when
   off (OpenApiEndpointIT green; `docs/api/openapi.json` unchanged). Error band **4275-4279** (4275
   retrain-running 409, 4276 not-found, 4277 invalid payload; 4278-4279 reserved); reuse 1130/1132 (module
   gate), 1800 (STAFF `RoleGuard`).

## Deviations / surprises

- **`tenant_calcom_idx` made NON-unique (deviation from the model's first draft).** A COMPOUND
  sparse+unique index `{tenantId, calComBookingUid}` is NOT sparse when `tenantId` is always present, so
  every directly-seeded appointment (uid=null) collided on the null key → `DuplicateKeyException` on the
  2nd insert per tenant (the documented `Tenant.zitadelOrgId` compound-null lesson; the RE `Listing`
  tracked-phone index has the same latent issue but isn't exercised by multi-null inserts). FD-1 seeds rows
  directly and does no live Cal.com sync, so uniqueness isn't needed yet; the index is a plain lookup now,
  to be re-tightened via a `partialFilterExpression` unique index (docs WHERE the uid exists) when
  FD-3/live Cal.com sync lands. This was the only test failure and is fixed.
- **`AppointmentImportController` (the optional HMAC CSV intake) was deliberately skipped** — the plan
  marks it optional; the demo seeds directly and the staff CRUD covers seeding. Keeps FD-1 tight.
- **`FrontDeskNoShowScoringService.features(...)` is `public`** (not package-private like CF-1's) so the
  PHI-fence IT can assert the vector width/contents directly — it's a pure function on logistics metadata,
  exposing it leaks nothing.
- **`openapi.json` not hand-edited** — the plan referenced `src/main/resources/openapi.json` (doesn't
  exist); the advisory spec is `docs/api/openapi.json`, auto-refreshed by the `verifyOpenApi` Gradle task,
  and the gated controllers are absent from it when the module is OFF by default. OpenApiEndpointIT (which
  only asserts the committed spec is non-empty) stays green; no manual cp1252 edit was needed.

---

# FD-2 — FrontDesk IQ: risk-tiered confirmation + recall/recare re-engagement — Progress Ledger

> Crash-recovery source of truth for `frontdesk-iq-phase-2-prevention-recall`
> (off `main` @ `15f98da`, FD-1 merged via PR #94). Appends to the FD-1 ledger above (same
> `frontdesk-iq-flagship` lineage).
>
> Spec: `~/.claude/plans/frontdesk-iq-flagship.md` §2 (FD-2 sub-phase) + §0 fence F3 (generic outbound copy).
> Error band: **4280-4284** (reserved — FD-2 mints none; reuses AI 1200-1203 + Twilio 2530-2532).

## Goal (FD-2)

(a) **Risk-tiered confirmation** on FD-1's `APPOINTMENT_RISK_SCORED` — HIGH → an extra confirmation ask;
LOW/MEDIUM → a light reminder (**no deposit**, unlike CF-2's salon path). (b) **Recall/recare
re-engagement** — a nightly sweep that finds lapsed patients (last visit > window, no upcoming) and enrolls
them into the practice's recall `Sequence` (the shipped engine) + sends a generic recare nudge. **All
outbound copy is GENERIC and PHI-free (fence F3)** — "time for your visit", never a procedure / provider /
visit-type. The F3 fence is the PHI headline and is made *provable* by a release-blocking IT.

## Design as built

### The risk-tiered confirmation subscriber (mirrors CF-2 `RiskTieredPreventionService`, minus the deposit)
- `module/frontdesk/automation/FrontDeskConfirmationService.java` — a `@PostConstruct` subscriber on
  `APPOINTMENT_RISK_SCORED`, the line-shape mirror of `chairfill/automation/RiskTieredPreventionService`:
  `events.stream().filter(type).flatMap(handle)` + synthetic `TenantContext` + a visible-for-test
  `handle(event)`. **Branch (no deposit path — health doesn't deposit):** HIGH → `confirm=true` (an extra
  confirmation ask); LOW/MEDIUM → `confirm=false` (a light reminder). Copy from `ConfirmationCopyService`
  (best-effort) → a generic deterministic fallback. **Gates:** module-membership re-check (defense-in-depth,
  HARD GATE 2), the `sms-opt-out` consent tag, phone-present, and a per-contact rolling frequency cap
  (`kmosf.frontdesk.confirmation.max-per-contact-per-window` over `…window-hours`).
- `module/frontdesk/automation/ConfirmationLog.java` + `ConfirmationLogRepository.java` — the idempotency +
  frequency-cap ledger, mirror of `ReminderLog`/`ReminderLogRepository` **minus `depositRequired`** (carries
  a `confirmation` boolean instead). Unique on `(tenantId, appointmentId)` — inserted **FIRST**, so a
  re-fired event loses on `DuplicateKeyException` = zero duplicate. `(tenantId, contactId, sentAt)` backs the
  frequency cap.

### The F3 generic-copy fence (the PHI headline) — provable at TWO layers
- `module/frontdesk/ai/ConfirmationCopyService.java` — the `ReminderCopyService` sibling (per-tenant
  Anthropic key + house-key fallback, `AiUsageRecorder` budget gate, WireMock-able base-url, AI codes
  1200-1203 reused). **Fence F3 by construction, not a bolt-on:**
  1. **The model is given NO clinical input.** Its `ConfirmationContext` carries ONLY
     `{clientFirstName, appointmentWhen, brandTone, confirm}` — there is deliberately **no service / provider /
     visit-type field** (unlike the salon `ReminderContext`, which carries `stylistName` + `lastService`). The
     record physically cannot transport a clinical token to the LLM.
  2. **The system prompt hard-forbids inventing one** — "GENERIC copy only … NEVER name, describe, guess, or
     invent any procedure, treatment, diagnosis, symptom, medication, test, body part, department, specialty,
     provider name, or visit type."
- The **deterministic fallback** (`FrontDeskConfirmationService.genericTemplate`) references only the first
  name + the time — there is no parameter through which a clinical token could enter.
- **The release-blocking IT** asserts a `FORBIDDEN_TOKENS` set (root canal/crown/procedure/diagnos/oncology/
  prescription/refill/hygiene/wellness/recall/dentist/doctor/provider/new-patient/… 30+ tokens) is **absent
  from every outbound body** — the Claude path, the fallback, AND the recall nudge.

### The recall/recare sweep (the shipped Sequence engine + a generic nudge)
- `module/frontdesk/automation/RecallDetectorJob.java` — a nightly `@Scheduled` per-tenant sweep (the
  `CoverageNudgeJob` posture), config cron `0 30 3 * * *` (after the FD-1 scorer's 02:45). Per frontdesk
  tenant (ACTIVE + `enabledModules ∋ "frontdesk"`): loads all appointments once, computes **lapsed contacts**
  = most-recent visit (`lastVisitAt` or a COMPLETED appt's `scheduledStart`) older than
  `kmosf.frontdesk.recall.window-days` (default 180) **AND no upcoming (SCHEDULED/CONFIRMED future)
  appointment**. For each: idempotent per `(tenant, contact, periodKey=ISO-week)` via a `RecallLog`
  ledger-insert-FIRST, then **(a) enroll** into the tenant's ACTIVE recall `Sequence` (matched by name
  `kmosf.frontdesk.recall.sequence-name`, default `frontdesk-recall`, via the reused
  `SequenceCrudService.enroll`) **and (b) send a generic recare nudge SMS** (consent-gated, F3-generic). Both
  best-effort.
- `module/frontdesk/automation/RecallLog.java` + `RecallLogRepository.java` — the recall idempotency ledger,
  unique on `(tenantId, contactId, periodKey)` (the `CoverageNudgeLog` pattern).

### The baseline WorkflowRule seeder (the ChairFillReminderAutomation analogue)
- `module/frontdesk/automation/FrontDeskReminderAutomation.java` — seeds an owner-tunable static `SEND_SMS`
  `WorkflowRule` on `APPOINTMENT_RISK_SCORED` per frontdesk tenant (idempotent on the rule name, on
  `ApplicationReadyEvent`). A visible, editable placeholder; the personalized/PHI-free logic stays in the
  subscriber (the v1 caveat: the payload carries no resolved phone, so the generic dispatcher safely skips).

## Files

### New (FD-2)
- `module/frontdesk/automation/FrontDeskConfirmationService.java` (the subscriber, minus deposit)
- `module/frontdesk/automation/ConfirmationLog.java` + `ConfirmationLogRepository.java`
- `module/frontdesk/ai/ConfirmationCopyService.java` (the F3-fenced Claude drafter)
- `module/frontdesk/automation/RecallDetectorJob.java` (the recall sweep)
- `module/frontdesk/automation/RecallLog.java` + `RecallLogRepository.java`
- `module/frontdesk/automation/FrontDeskReminderAutomation.java` (the baseline rule seeder)

### Touched (additive only)
- `module/frontdesk/FrontDeskAutoConfiguration.java` (+4 FD-2 `@Bean`s + the FD-2 class-doc section)
- `repository/SequenceRepository.java` (+ derived finder `findAllByTenantIdAndStatus` — additive, harmless
  to existing callers; needed because the recall job runs outside a request `TenantContext`)
- `controller/advice/GlobalErrorHandler.java` (+4280-4284 doc band)

### Reused (NOT copied)
- `service/sequence/SequenceCrudService` + the shipped `SequenceEngine`/`Sequence`/`SequenceEnrollment`
  (recall cadence), `integration/twilio/TwilioSmsService` (SMS), `service/ai/AiUsageRecorder` +
  `IntegrationConnectionRepository` (the AI budget/key spine), `ContactRepository`, `TenantRepository`,
  `WorkflowRule`/`WorkflowRuleRepository`, the chairfill `NoShowRisk.TIER_*` constants.

### Tests
- `src/test/java/.../module/frontdesk/FrontDeskConfirmationIT.java` — (1) HIGH → an extra confirmation ask,
  generic, no deposit; (2) LOW → one light reminder, generic; (3) **F3 fence on the deterministic fallback**
  (no clinical/provider token); (4) idempotent on a re-fired event (zero duplicate, one ledger row); (5) a
  non-frontdesk tenant is a hard no-op; (6) TCPA — an opted-out contact gets no SMS; (7) best-effort — a
  Claude 500 still sends a generic reminder (`personalized=false`); (8) recall — a lapsed contact enrolls into
  the recall Sequence + gets a generic nudge while a contact WITH an upcoming appointment is NOT recalled, and
  the sweep is idempotent per period. **Every outbound-body assertion runs through the `FORBIDDEN_TOKENS`
  generic-copy guard (fence F3).** WireMock for Anthropic; `@MockitoBean TwilioSmsService` capture seam.

## Validation status

- `./gradlew compileJava compileTestJava` — GREEN.
- `./gradlew cleanTest test --tests "*FrontDesk*IT" --tests "*chairfill.RiskTieredPreventionIT"
  --tests "*chairfill.NoShowRiskScoringIT" --tests "*LeadScoringV2IT" --tests "*OpenApiEndpointIT"`
  — **GREEN** (force-clean). Per-class (tests/failures/errors): **FrontDeskConfirmationIT 8/0/0**;
  FrontDeskNoShowScoringServiceIT 10/0/0; RiskTieredPreventionIT (CF-2) 5/0/0; NoShowRiskScoringIT (CF-1)
  8/0/0; LeadScoringV2IT 4/0/0; OpenApiEndpointIT 2/0/0.
- Plus `./gradlew test --tests "*frontdesk.NoShowRiskControllerIT"` (FD-1 controller, not caught by the
  `*FrontDesk*IT` glob) — **7/0/0**, confirming the FD-1 surface stays green with the FD-2 beans loaded.

## Hard gates

1. **PHI-free outbound (F3)** — every confirmation/reminder/recall body is generic; the `FORBIDDEN_TOKENS`
   assertion (30+ clinical/provider/visit-type tokens) passes on the Claude path, the fallback, and the recall
   nudge. The drafter's `ConfirmationContext` physically cannot carry a clinical token (structural fence).
2. **FD-1 + CF-1/CF-2 + the lead-scorer untouched** — RiskTieredPreventionIT (CF-2) 5/0/0, NoShowRiskScoringIT
   (CF-1) 8/0/0, LeadScoringV2IT 4/0/0, FD-1 ITs 10/0/0 + 7/0/0 all pass unchanged. FD-2 is a parallel
   `frontdesk` subscriber + two scheduled jobs; it touches no salon `Booking`/deposit path. The only shipped
   file touched outside `frontdesk` is `SequenceRepository` (a purely additive derived finder).
3. **Consent/TCPA + idempotent log** — opt-out tag + frequency cap gates; `ConfirmationLog` insert-FIRST
   (unique on tenant+appointment) → no duplicate on a re-fired event; `RecallLog` insert-FIRST (unique on
   tenant+contact+period) → no duplicate recall per period. Both verified by ITs.
4. **Module-gated, blast radius zero, best-effort** — all FD-2 beans live under the `@ConditionalOnProperty`
   `frontdesk` gate (no bean for a non-frontdesk server); the subscriber re-checks `enabledModules`
   membership; the recall sweep filters to ACTIVE frontdesk tenants. Every Claude/SMS call is `onErrorResume`
   best-effort (degrade to generic / skip, never error). Error band **4280-4284** reserved; FD-2 mints no new
   codes (reuses AI 1200-1203, Twilio 2530-2532).

## Deviations / surprises

- **Recall does BOTH enroll AND nudge (plan said "enroll … OR … nudge").** The shipped `SequenceEngine` is
  email-only (`EMAIL_SEND`/`WAIT`/`BRANCH`/`EXIT`) and FrontDesk's outbound channel is SMS, so a Sequence
  alone wouldn't produce the demo-visible SMS nudge. The job therefore enrolls into the recall Sequence (the
  marquee "shipped engine" reuse — visible on the contact, drives any email cadence) AND sends the generic
  recare SMS nudge (the immediate, demo-visible re-engagement) under ONE idempotent ledger insert. Both are
  asserted by the IT. If a tenant has no recall Sequence, the nudge still fires (enroll is skipped cleanly).
- **No recall domain event emitted.** An earlier draft emitted a recall event but it would have re-entered the
  `APPOINTMENT_RISK_SCORED` subscriber (and carried no appointmentId) — confusing and a latent feedback
  footgun. Dropped: the ledger row + the enrollment + the SMS are the observable outcomes the IT asserts; no
  event is needed.
- **`SequenceRepository.findAllByTenantIdAndStatus` added.** The marker repo had no tenant-scoped finder and
  the recall job runs outside a request `TenantContext`, so a derived finder with an explicit `tenantId`
  predicate was required (the `BookingRepository`/`AppointmentRepository` posture). Additive; existing
  `SequenceCrudService`/`SequenceController` callers are unaffected.
- **`ConfirmationCopyService.ConfirmationContext` deliberately drops the salon record's `stylistName` +
  `lastService` fields.** This is the structural half of fence F3 — the strongest possible guard is to make
  the clinical token un-passable, not merely forbidden in the prompt.

---

# FD-3 — FrontDesk IQ: PHI-free voicemail-to-callback (HS-1 seam + fence F2) — Progress Ledger

> Crash-recovery source of truth for `frontdesk-iq-phase-3-voicemail-callback`
> (off `main` @ `c271d48`, FD-2 merged via PR #95). Appends to the FD-1/FD-2 ledger above (same
> `frontdesk-iq-flagship` lineage).
>
> Spec: `~/.claude/plans/frontdesk-iq-flagship.md` §2 (FD-3 sub-phase + the F2 seam option (A)) + §0 fence
> F2 (raw transcript never persisted/indexed). Error band: **4285-4289** (reserved — FD-3 mints none;
> reuses AI 1200-1203, Twilio signature 4000-4003, Activity 1300).

## Goal (FD-3)

An after-hours health-practice voicemail → a **logistics-only** extraction (name / callback number /
intent bucket — `SCHEDULING`/`BILLING`/`PRESCRIPTION_REFILL_REQUEST`/`GENERAL_CALLBACK`/`OTHER`, **never
clinical detail**) → a front-desk callback `Activity(CALL, INBOUND)` + best-effort notify the desk.
Reuses the **HS-1 per-tenant voicemail strategy seam** end-to-end. The headline is **fence F2**: the raw
transcript is **NEVER persisted or indexed** — made provable by a release-blocking IT.

## Design as built

### The F2 seam — `persistTranscript()` (the ONE divergence; keeps mole/multi-trade byte-equivalent)
- `integration/twilio/voice/extract/VoicemailExtractionStrategy.java` — added a
  **`default boolean persistTranscript() { return true; }`** to the interface. **Mole + multi-trade do NOT
  override it** → they keep the default `true` → byte-identical behavior (the `TwilioVoicemailIT` /
  `HomeServicesVoicemailIT` / `HomeServicesVoicemailFieldServiceDisabledIT` regression gates). The health
  strategy returns `false`. This is the additive, default-preserving option (A) the plan recommends.
- `integration/twilio/voice/TwilioVoicemailService.logCallActivity(...)` — now receives the resolved
  `strategy` (threaded `handleTranscript → createLeadAndNotify → logCallActivity`) and gates the body +
  recording pointer on `strategy.persistTranscript()`:
  - **`true` (mole + multi-trade):** `Activity.body` = the raw transcript exactly as before; the
    `recordingSid`/`recordingUrl` pointer is retained in the payload — **byte-identical** to the shipped
    construction (same payload keys, same order, same body expression).
  - **`false` (health):** `Activity.body` = the fixed marker `TwilioVoicemailService.TRANSCRIPT_REDACTED_MARKER`
    = `"(voicemail transcript not retained — front-desk callback)"`; the `recordingSid`/`recordingUrl`
    (a path back to the spoken words) are **omitted** from the payload. Only the strategy's logistics-only
    `extractedJson` + call/from metadata survive. (`transcriptionSource` is the source enum name, not PHI.)
- **Defense-in-depth (cited, not relied upon):** `EmbeddingPipeline.indexActivity` indexes only NOTE/EMAIL
  (line 116), so a `CALL` Activity is never embedded regardless — F2's transcript-suppression is the
  primary fence, CALL-not-indexed is the second layer.

### The health strategy (mirror of `MultiTradeExtractionStrategy`, minus the WO + minus transcript)
- `integration/twilio/voice/extract/HealthFrontDeskExtractionStrategy.java` — `verticalKey()="health-frontdesk"`,
  a thin caller over the unchanged `VoicemailExtractionService` transport (AI codes 1200-1203 reused). Owns
  only its **logistics-only strict-JSON system prompt** (explicitly instructs the model to capture name /
  callbackNumber / intentBucket / callbackRequested and to **drop ALL clinical/symptom/medication detail** —
  the prompt is the last fence, never the only one), its model choice, and the map into `VoicemailLeadDetails`.
  `persistTranscript()=false`. `toDraftWorkOrder` is **always null** (health creates no WorkOrder). Best-effort:
  an AI failure degrades to `HealthIntakeExtraction.empty()` (OTHER bucket) → a still-usable callback from
  caller-ID, never the transcript. Selected per-tenant via `IntegrationConnection(twilio).config.voicemailVertical
  = "health-frontdesk"`; NMM (no key) still defaults to mole → zero blast radius.
- `integration/twilio/voice/extract/HealthIntakeExtraction.java` — the logistics-only record
  `{name, callbackNumber, intentBucket, callbackRequested}` + the `IntentBucket` enum
  `{SCHEDULING, BILLING, PRESCRIPTION_REFILL_REQUEST, GENERAL_CALLBACK, OTHER}` + lenient `fromWire`
  (unknown → OTHER) + `wire()`/`label()`. **Structural fence F2:** the record has NO symptom/diagnosis/
  procedure/drug/dosage slot, so the spoken clinical detail cannot be captured even if the model emitted it.
  A refill *request* routes as the `PRESCRIPTION_REFILL_REQUEST` logistics bucket; the drug name is not kept.

### The callback Activity + notify (reused pipeline, PHI-free payload)
- The existing `TwilioVoicemailService` pipeline (signature-verify 4000-4003, ledger-insert-FIRST idempotency,
  find-or-create Contact, `Activity(CALL, INBOUND)`, best-effort notify + auto-ack) is **reused unchanged**
  except for the `persistTranscript()` gate. The health voicemail yields: a Contact, a callback `Activity`
  whose body is the redaction marker and whose payload carries only `{callSid, fromNumber, extractedJson:
  {name, callbackNumber, intentBucket, callbackRequested}, transcriptionSource}`, and a PHI-free notify
  (the summary one-liner names only the logistics bucket, e.g. "Callback from Dana — Prescription refill —
  callback requested").

## Files

### New (FD-3)
- `integration/twilio/voice/extract/HealthFrontDeskExtractionStrategy.java` (the strategy; `persistTranscript()=false`)
- `integration/twilio/voice/extract/HealthIntakeExtraction.java` (logistics-only record + `IntentBucket` enum)

### Touched (additive, default-preserving)
- `integration/twilio/voice/extract/VoicemailExtractionStrategy.java` (+`default boolean persistTranscript()`)
- `integration/twilio/voice/TwilioVoicemailService.java` (thread `strategy` into `logCallActivity`; gate
  body + recording pointer on `persistTranscript()`; add `TRANSCRIPT_REDACTED_MARKER`)
- `controller/advice/GlobalErrorHandler.java` (+4285-4289 doc band)

### Reused, NOT copied / NOT changed
- `VoicemailExtractionStrategyResolver` (selection by `voicemailVertical`), `VoicemailExtractionService`
  (transport/budget/parse — codes 1200-1203), the whole `TwilioVoicemailService` signature-verify +
  idempotency + Contact + notify + auto-ack pipeline, `VoicemailLeadDetails` (`draftWorkOrder=null` for health).

### Tests
- `src/test/java/.../integration/twilio/voice/HealthFrontDeskVoicemailIT.java` — (1) **THE MARQUEE /
  release-blocking F2 test**: a `voicemailVertical="health-frontdesk"` signed transcription callback whose
  transcript mentions clinical detail ("blood pressure", "Lisinopril", "dizzy") → one Contact, one callback
  `Activity(CALL, INBOUND)` with `extractedJson.intentBucket=PRESCRIPTION_REFILL_REQUEST` + callbackNumber,
  **`body` == the redaction marker (NOT the transcript)**, payload has **no `recordingSid`/`recordingUrl`**
  and no transcript, and the **entire serialized Activity contains NONE of the clinical tokens** (the F2
  assertion); the Anthropic transport called once; notify fired but PHI-free (no clinical token in any SMS
  body). (2) best-effort — a Claude 500 still creates a callback Activity and the transcript is **still
  redacted** on the degraded path. WireMock for Anthropic; `@MockitoBean TwilioSmsService`/`EmailService`.

## Validation status

- `./gradlew compileJava compileTestJava` — GREEN.
- `./gradlew cleanTest test --tests "*HealthFrontDeskVoicemailIT" --tests "*TwilioVoicemailIT"
  --tests "*HomeServicesVoicemailIT" --tests "*HomeServicesVoicemailFieldServiceDisabledIT"
  --tests "*FrontDeskConfirmationIT" --tests "*OpenApiEndpointIT"` — **GREEN** (force-clean). Per-class
  (tests/failures/errors): **HealthFrontDeskVoicemailIT 2/0/0**; **TwilioVoicemailIT (mole gate) 6/0/0**;
  **HomeServicesVoicemailIT (multi-trade gate) 4/0/0**; **HomeServicesVoicemailFieldServiceDisabledIT
  (multi-trade degrade gate) 2/0/0**; FrontDeskConfirmationIT 8/0/0; OpenApiEndpointIT 2/0/0. (24 total,
  0 failures.)

## Hard gates

1. **Mole + multi-trade voicemail BYTE-EQUIVALENT** — `persistTranscript()` defaults `true`; the mole + the
   two multi-trade ITs pass **unchanged** (6/0/0, 4/0/0, 2/0/0). The only change to a shipped file
   (`TwilioVoicemailService`) is gated behind the default-true bit, so the default path is byte-identical.
2. **F2 fence (the PHI headline)** — the health voicemail Activity stores NO raw transcript (body = the
   redaction marker), NO recording pointer, and NO clinical token; the release-blocking
   `clinicalVoicemail_createsCallbackActivity_withNoTranscriptAndNoClinicalToken` IT asserts a clinical
   forbidden-token set is absent from the entire serialized Activity (and from every outbound SMS body). The
   structural fence (no clinical slot on `HealthIntakeExtraction`) means PHI can't be captured even upstream.
3. **Module-gated / per-tenant, blast radius zero, best-effort, error band 4285-4289** — selection is by the
   per-tenant `voicemailVertical="health-frontdesk"` (NMM/HS/ChairFill/RealEstate unaffected); the new
   strategy is an additive `@Component` (the resolver auto-discovers it). Every AI/notify call is
   `onErrorResume` best-effort (a callback is never dropped). FD-3 mints no new error codes (reuses AI
   1200-1203, Twilio signature 4000-4003, Activity 1300); the 4285-4289 band is reserved + documented.

## Deviations / surprises

- **Intent-bucket vocabulary follows the PLAN (`SCHEDULING / BILLING / PRESCRIPTION_REFILL_REQUEST /
  GENERAL_CALLBACK / OTHER`), not the alternate set in the task prose** (`SCHEDULE/RESCHEDULE/CANCEL/BILLING/
  NEW_PATIENT/OTHER`). The plan §0/§2 + the demo script + the marquee F2 test all key on
  `PRESCRIPTION_REFILL_REQUEST` as the load-bearing demo bucket (a refill *request* is the canonical
  "rides up to the clinical line, fenced" example), so the plan's set is the source of truth.
- **No `FRONTDESK_CALLBACK_CREATED` event emitted (plan marked it optional).** The reused pipeline already
  emits `VOICEMAIL_LEAD_CREATED` (carrying contactId + activityId) for the callback inbox to consume; adding
  a second, health-only emit would have added a second seam bit and blast-radius surface for no FD-3
  behavior. Kept the seam to the single `persistTranscript()` bit (the briefing's "keep it tight"). Trivial
  to add additively in FD-5 if the FE callback inbox wants a distinct signal.
- **No `VoicemailRetention` opt-in blob built (plan marked it optional).** The default + only behavior is to
  drop the transcript after extraction (the strongest F2 posture). A tenant insisting on verifiable
  transcripts is a separately-priced compliance-tier conversation (plan §0/D4) — deliberately not built.
- **The recording SID/URL are suppressed alongside the transcript for the health vertical.** The plan's F2
  text focuses on the transcript; the recording pointer is a re-fetchable path back to the spoken words, so
  omitting it from the payload closes that gap too. (For mole/multi-trade the pointer is retained
  byte-identically — the default-true branch is unchanged.)

---

# FD-4 — FrontDesk IQ: HIPAA-safe review-reply (CF-4 + GBP generalization, fence F4) — Progress Ledger

> Crash-recovery source of truth for `frontdesk-iq-phase-4-review-reply`
> (off `main` @ `74b58c6`, FD-3 merged via PR #96). Appends to the FD-1/FD-2/FD-3 ledger above (same
> `frontdesk-iq-flagship` lineage).
>
> Spec: `~/.claude/plans/frontdesk-iq-flagship.md` §2 (FD-4 sub-phase) + §0 fence F4 (HIPAA-guardrail
> review-reply prompt + never-auto-post). Error band: **4290-4294**.

## Goal (FD-4)

Generalize the shipped review-reply drafter for a health practice with a **hard HIPAA guardrail** (the
flagship's signature demo). The public reply may only **thank / apologize for the experience /
invite-offline**; it **never confirms the reviewer was a patient, never names a procedure / treatment /
diagnosis / medication, never references any clinical detail** — even on an adversarial review that
explicitly mentions a procedure. Draft → **human approve** (copy-ready) / skip; **never auto-post**. Calls
the **unchanged** `GbpReplyDraftService.draftReply(review, systemPromptOverride, exemplars)` overload with a
HIPAA system prompt (NMM/CF-4 byte-equivalence). The F4 fence is made *provable* by a release-blocking IT.

## Design as built

### The HIPAA-guardrail prompt + the unchanged drafter (CF-4 D4 — generalize, don't fork)
- `module/frontdesk/reviews/FrontDeskReviewReplyService.java` — the CF-4 `SalonReviewReplyService` sibling.
  Calls the **unchanged** `GbpReplyDraftService.draftReply(review, healthSystemPrompt(), exemplars)` overload
  — `GbpReplyDraftService` is **not modified** (NMM/GBP + ChairFill pass their own prompts; the health prompt
  is additive + per-call). Ledger-insert-FIRST idempotency on the (synthetic/supplied) review id (synthetic =
  `health-pasted/<uuid>`), best-effort draft → a generic **HIPAA-safe** fallback, emit
  `GBP_REVIEW_REPLY_DRAFTED`.
- **The `HEALTH_DEFAULT_SYSTEM_PROMPT` (fence F4):** four ABSOLUTE rules that override the review/any other
  instruction — (1) NEVER confirm/imply the reviewer is/was a patient; (2) NEVER name/echo ANY procedure /
  treatment / diagnosis / condition / medication / test / clinical detail **even if the review names one**;
  (3) may ONLY thank-for-feedback + general (non-clinical) apology + invite-to-call-the-office-offline;
  (4) 2-4 warm sentences, first-name OK, no invented facts/markdown. Plus a closing "when unsure, leave it
  out — a generic thank-you-and-please-call-us is always safe." The per-tenant
  `kmosf.frontdesk.review-system-prompt` is appended as a brand-tone hint (cannot relax the guardrail — the
  guardrail clauses come first).
- **The generic fallback is HIPAA-safe by construction:** it thanks / apologizes / invites-offline and
  **deliberately does NOT echo the review text**, so an adversarial review's clinical term cannot bleed into
  the fallback when Claude is unavailable.

### The deterministic HIPAA lint (the backstop — RE-4 `FairHousingLint` / FD-2 F3-lint pattern)
- `module/frontdesk/reviews/HipaaReplyLint.java` — a pure, stateless, side-effect-free utility (no Spring
  bean, the `FairHousingLint` posture). Two banned-phrase families scanned over the **drafted reply**:
  **PATIENT_STATUS** confirmation phrases ("being our patient", "thank you for choosing our practice", "your
  treatment", "your procedure", "your appointment", "your visit", …) and **CLINICAL** vocabulary (crown, root
  canal, filling, extraction, surgery, biopsy, x-ray, prescription, diagnosis, chemo, spay/neuter, …).
  Case-insensitive whole-word (single tokens, via a word-boundary check) / substring (multi-word phrases)
  match — conservative (a dismissible false positive is cheaper than a missed disclosure). Returns
  `List<HipaaFlag{category, term, snippet}>`; `isClean()` convenience. **It does NOT block** — the lint flags
  + the mandatory human approval are the gate.

### The draft → approve/skip queue (mirror `SalonReviewReplyController`, module + STAFF gated)
- `module/frontdesk/controller/FrontDeskReviewReplyController.java` — `@ConditionalOnProperty(frontdesk)` +
  `TenantModuleRegistry.requireEnabled` per handler + `RoleGuard.requireRole("STAFF")`:
  - `POST /frontdesk/reviews/draft` — paste-in (blank comment → **4290**) → draft + lint → DRAFTED.
  - `GET /frontdesk/reviews` — `listDrafted` (most-recent first), each row carrying its live lint flags.
  - `POST /frontdesk/reviews/{id}/approve` — DRAFTED → **POSTED, copy-ready** (`postedAt` set, **NO live
    Google call** — the demo path has no GBP OAuth; the staffer copies the approved reply into the GBP
    console), emits `GBP_REVIEW_REPLY_POSTED`. Leaves the queue. Same-status guard → **4291**; not-found →
    **4292**.
  - `POST /frontdesk/reviews/{id}/skip` — DRAFTED → SKIPPED. Same guards.
- **Why a FrontDesk-specific queue (not the shared `GbpReviewReplyAdminController`):** the shared admin
  surface is ADMIN-gated and posts **live** to Google via `GbpApiClient.postReply`; the FD-4 demo path is
  STAFF-gated and **copy-ready (never auto-post)**. Reusing it would force a GBP connection + an ADMIN role
  for the demo. The FD-4 surface reuses the shared `GbpReviewReply` model + repository + `DRAFTED/POSTED/
  SKIPPED` status machine **unchanged** (no model/enum change → NMM/CF-4 byte-equivalent), exposing
  approve=copy-ready (POSTED without the Google call) through its own controller/service.

### Lint flags surfaced without a model change (the `DraftedReply` DTO)
- The byte-equivalent `GbpReviewReply` model carries **no flag field** (changing it would risk NMM/CF-4
  byte-equivalence). FD-4 returns a small `FrontDeskReviewReplyService.DraftedReply{reply, hipaaFlags}` DTO;
  the flags are computed at draft time **and re-computed deterministically on every list/approve/skip read**,
  so the staffer always sees the current screen of the drafted text. No persistence, no schema change.

### Exemplar/voice source (reuse ChairFill's ledger-backed source)
- `frontDeskReplyExemplarSource` `@Bean` reuses the ChairFill `LedgerReplyExemplarSource` (the tenant's own
  POSTED replies, no embeddings, CI-robust) for on-brand tone. A FrontDesk-qualified bean so it never
  collides with ChairFill's (both modules can be on). Toggle via `kmosf.frontdesk.review-exemplars-enabled`
  (default true), limit `kmosf.frontdesk.review-exemplar-limit` (default 3). The guardrail prompt — not the
  exemplars — is the load-bearing part of F4.

## Files

### New (FD-4)
- `module/frontdesk/reviews/HipaaReplyLint.java` (the deterministic HIPAA lint + `HipaaFlag` record)
- `module/frontdesk/reviews/FrontDeskReviewReplyService.java` (the drafter + draft→approve/skip queue + the
  `HEALTH_DEFAULT_SYSTEM_PROMPT` + the HIPAA-safe generic fallback + the `DraftedReply` DTO)
- `module/frontdesk/controller/FrontDeskReviewReplyController.java` (the STAFF/module-gated paste-in + queue)

### Touched (additive only)
- `module/frontdesk/FrontDeskAutoConfiguration.java` (+2 FD-4 `@Bean`s — `frontDeskReplyExemplarSource`,
  `frontDeskReviewReplyService` — + the FD-4 class-doc section; imports `GbpReplyDraftService`, the chairfill
  `ReplyExemplarSource`/`LedgerReplyExemplarSource`, `GbpReviewReplyRepository`)
- `controller/advice/GlobalErrorHandler.java` (+4290-4294 doc band)

### Reused, NOT copied / NOT changed
- `integration/gbp/GbpReplyDraftService` (the additive `draftReply(review, prompt, exemplars)` overload —
  **byte-unchanged**), `model/integration/GbpReviewReply` + `repository/gbp/GbpReviewReplyRepository` (the
  shared ledger/queue + `DRAFTED/POSTED/SKIPPED` status machine — **unchanged**), the chairfill
  `reviews/ReplyExemplarSource` + `LedgerReplyExemplarSource`, `automation/DomainEventType`
  (`GBP_REVIEW_REPLY_DRAFTED`/`_POSTED` — existing), `RoleGuard`, `TenantModuleRegistry`.

### Tests
- `src/test/java/.../module/frontdesk/FrontDeskReviewReplyIT.java` — (1) **THE HEADLINE / release-blocking
  F4 test**: an adversarial 1-star review naming a procedure ("the dentist botched my crown and the root
  canal was a disaster … needed a prescription") → a DRAFTED reply that contains **NONE** of a forbidden
  patient-status/procedure token set, and the **HIPAA-guardrail system prompt** (HIPAA / "NEVER confirm" /
  "procedure, treatment, diagnosis") was the one sent to Claude; the review text WAS sent as model input
  (the guardrail constrains the *output*, we don't strip the input); and **no Google/GBP call was made**
  (never-auto-post). (2) the deterministic lint catches a crafted leak (PATIENT_STATUS + CLINICAL flags on a
  leaky reply, clean on a compliant one) + the leak surfaces on the queue row. (3) approve → POSTED,
  copy-ready, leaves the queue, **no Google call**, re-approve → 4291. (4) best-effort — a Claude 500 →
  a generic HIPAA-safe fallback (no error, DRAFTED, non-blank, contains the reviewer name, **still clean of
  the forbidden set** even though the review named procedures, still no post). (5) skip → SKIPPED + leaves the
  queue. (6) blank comment → 4290; non-frontdesk tenant → 1132 (no row, no Claude call). WireMock for
  Anthropic (the `SalonReviewReplyIT` pattern); no GBP base-url override is wired (so any GBP PUT would be a
  never-auto-post bug the IT would catch).

## Validation status

- `./gradlew compileJava compileTestJava` — GREEN.
- `./gradlew cleanTest test --tests "*FrontDeskReviewReplyIT" --tests "*SalonReviewReply*IT"
  --tests "*GbpReply*IT" --tests "*FrontDeskConfirmationIT" --tests "*OpenApiEndpointIT"` — **GREEN**
  (force-clean). Per-class (tests/failures/errors): **FrontDeskReviewReplyIT 7/0/0**; **SalonReviewReplyIT
  (CF-4 byte-equivalence) 8/0/0**; **GbpReplyDraftServiceIT (GBP draft-reply byte-equivalence) 4/0/0**;
  FrontDeskConfirmationIT (FD-2 regression) 8/0/0; OpenApiEndpointIT 2/0/0.
- Plus `./gradlew cleanTest test --tests "*GbpReviewReply*IT" --tests "*GbpReviewPoller*IT"` (the GBP
  review-reply byte-equivalence gates the `*GbpReply*IT` glob does not match) — **GREEN**:
  **GbpReviewReplyAdminIT 6/0/0**; **GbpReviewPollerIT 2/0/0**.

## Hard gates

1. **F4 / HIPAA-safe (the headline)** — the drafted reply confirms no patient status + names no procedure
   even on an adversarial review that explicitly mentions a procedure; the release-blocking IT asserts a
   forbidden patient-status/procedure token set is **absent** from the draft (both the Claude path and the
   generic fallback), and the deterministic `HipaaReplyLint` catches a crafted leak. The guardrail prompt +
   the lint + the mandatory human approval are the three layers.
2. **`GbpReplyDraftService` untouched** — the file is byte-unchanged; FD-4 calls the existing additive
   overload with a per-call prompt. `GbpReplyDraftServiceIT` (4/0/0) + `SalonReviewReplyIT` (CF-4, 8/0/0) +
   `GbpReviewPollerIT` (2/0/0) + `GbpReviewReplyAdminIT` (6/0/0) all pass unchanged. The shared
   `GbpReviewReply` model/enum + the GBP admin surface are also unchanged (NMM/CF-4 byte-equivalent).
3. **Never auto-post** — drafting persists DRAFTED only; approve marks POSTED/copy-ready **without** any
   `GbpApiClient.postReply` / Google call (the IT asserts zero GBP PUTs across draft + approve + skip + the
   best-effort path; no GBP base-url is even wired). Staff approval is the gate (the GBP posture).
4. **Module-gated, zero blast radius, best-effort, error band 4290-4294** — the controller is
   `@ConditionalOnProperty(frontdesk)`-gated (absent from the OpenAPI spec when off → `openapi.json`
   unchanged) + `requireEnabled` per handler + STAFF `RoleGuard`. A Claude failure `onErrorResume`-degrades
   to a safe, never-leaky generic draft + the lint flags (never an error, never a dropped review). New codes
   **4290** blank paste-in (400), **4291** not-DRAFTED approve/skip (409), **4292** draft not found (404);
   4293-4294 reserved; AI 1200-1203 reused (the unchanged drafter), 1130/1132 module gate, 1800 STAFF.

## Deviations / surprises

- **`approve` = DRAFTED→POSTED *copy-ready* (no live Google call), not a new `APPROVED` status.** The plan
  asked for "approve (DRAFTED→APPROVED, copy-ready)", but the shared `GbpReviewReply.Status` enum is
  `DRAFTED/POSTED/SKIPPED` and **must not be modified** (NMM/CF-4 byte-equivalence + out of FD-4 scope). The
  CF-4 controller doc already defines "copy-ready" as the no-OAuth approve path ("edits the on-brand draft and
  copies it into the GBP console"). So FD-4 models approve as terminal **POSTED with `postedAt` set but no
  `GbpApiClient.postReply` call** — the demo "approved/copy-ready for manual paste-in" semantics — keeping the
  shared model/enum and the GBP admin queue byte-unchanged. (A dedicated `APPROVED` state would be a
  shared-model change touching CF-4/NMM; deliberately avoided.)
- **A FrontDesk-specific `FrontDeskReviewReplyController` rather than reusing `GbpReviewReplyAdminController`
  for the queue.** The shared admin controller is ADMIN-gated and posts live to Google; the FD-4 demo path is
  STAFF-gated + copy-ready + module-gated. A separate controller keeps the shared admin surface unchanged and
  gives FD-4 its own gating/semantics while reusing the shared model/repo/status-machine underneath.
- **Lint flags returned via a `DraftedReply` DTO, not persisted on `GbpReviewReply`.** Persisting flags would
  mutate the shared, byte-equivalent model. The flags are deterministic over the draft text, so re-computing
  them on every read (draft/list/approve/skip) costs nothing and avoids the schema change.
- **The service is named `FrontDeskReviewReplyService` (per the task's explicit file path), the plan's prose
  calls it `HealthReviewReplyService`.** Followed the task path; it also matches the sibling naming
  (`FrontDeskConfirmationService`, `FrontDeskNoShowScoringService`).
- **`openapi.json` not hand-edited** — the FD-4 controller is `@ConditionalOnProperty(frontdesk)`-gated and
  the module is OFF by default, so the new endpoints are absent from the advisory `docs/api/openapi.json`
  (the FD-1 `NoShowRiskController`/`AppointmentController` precedent). OpenApiEndpointIT stays green; no
  cp1252 edit needed (`git status` confirms `openapi.json` unchanged).

---

# FD-5a — FrontDesk IQ: recall-board + callback-inbox staff reads — Progress Ledger

> Crash-recovery source of truth for `frontdesk-iq-phase-5a-recall-callback-reads`
> (off `main` @ `a80b899`, FD-4 merged via PR #98). Appends to the FD-1..FD-4 ledger above (same
> `frontdesk-iq-flagship` lineage).
>
> Spec: FD-5 FE has 4 surfaces; two already have reads (risk-sorted day = `NoShowRiskController GET
> /frontdesk/risk/appointments`; review inbox = `FrontDeskReviewReplyController GET /frontdesk/reviews`).
> FD-5a adds the other two reads. Error band: **4295-4299** (reserved — FD-5a mints none; reuses the
> module gate 1130/1132 + STAFF `RoleGuard` 1800).

## Goal (FD-5a)

Two staff reads backing the FD-5b FE: **(1) the recall board** — who is due for recall/recare (the FD-2
lapsed selector, surfaced as a read), and **(2) the callback inbox** — the FD-3 after-hours health
voicemail callbacks. The headline constraint is **fence F2 carried into the read**: the callback inbox
returns logistics fields ONLY (name / callbackPhone / intentBucket) and **NEVER a transcript** — asserted
by the IT. Purely additive over FD-1..FD-3 collections; FD-1..FD-4 behavior untouched.

## Design as built

### `FrontDeskBoardController` (mirror `NoShowRiskController` / `WaitlistBoardController` /
### `ConciergeConversationController`)
- `module/frontdesk/controller/FrontDeskBoardController.java` — `@RequestMapping("/frontdesk")` +
  `@ConditionalOnProperty(kmosf.modules.frontdesk.enabled)` (absent from the OpenAPI spec when off) +
  per-handler `guard()` = `TenantModuleRegistry.requireEnabled("frontdesk")` then
  `RoleGuard.requireRole("STAFF")` (the 1130/1132 + 1800 posture). Two GET handlers.

### Recall board — `GET /frontdesk/recall`
- Replicates the FD-2 `RecallDetectorJob.lapsedContacts` selector **verbatim** (most-recent visit =
  `lastVisitAt` or a COMPLETED appointment's `scheduledStart`, older than the recall window AND no
  upcoming SCHEDULED/CONFIRMED future appointment), but keeps the **timestamp** per contact (the read
  needs `lastVisitAt` / `daysSinceLastVisit`; the job only needed the ids). One `findAllByTenantId`
  read (the FD-1 scorer posture). Shares the SAME window config `kmosf.frontdesk.recall.window-days`
  (default 180) the FD-2 job uses, so the read's lapsed set matches the sweep's.
- Enriched with **(a)** the contact's display name (`ContactRepository.findByTenantIdAndId`, best-effort
  — a missing contact never fails the read, the RE-5a `resolveLeadTier` posture) and **(b)**
  `nudgedThisPeriod` from the FD-2 `RecallLog` ledger (the new `findByTenantIdAndPeriodKey` finder,
  filtered to `nudged`, for the current ISO-week period key — the SAME period key the job stamps).
- Response: a `List<RecallDueDTO>` sorted **most-overdue first** (`daysSinceLastVisit` desc).

### Callback inbox — `GET /frontdesk/callbacks` (fence F2)
- Selects the FD-3 health callbacks via the new
  `ActivityRepository.findAllByTenantIdAndTypeAndDirectionAndBodyOrderByOccurredAtDesc(tenantId, CALL,
  INBOUND, TRANSCRIPT_REDACTED_MARKER)`. **The body-marker predicate is the F2 discriminator:** the
  redaction marker is produced ONLY by the health front-desk strategy (`persistTranscript()=false`); the
  mole / multi-trade voicemail verticals store the raw transcript as `body`, so this query cleanly
  selects the PHI-free health callbacks and excludes every other inbound-call Activity.
- Projects through `CallbackInboxItemDTO.from(Activity)`, which reads ONLY the logistics
  `payload.extractedJson` (name / callbackNumber / intentBucket / callbackRequested) + the caller-ID
  `payload.fromNumber` — it **never reads `body`** (the marker) or any recording pointer, and the DTO
  **has no transcript/body/recording field**, so the read can never surface the spoken words. The
  `callbackPhone` prefers the extracted `callbackNumber`, falling back to the caller-ID so the row is
  always dialable. Response: a `List<CallbackInboxItemDTO>`, newest first (`occurredAt` desc).

## Files

### New (FD-5a)
- `module/frontdesk/controller/FrontDeskBoardController.java` (the two reads, gated + STAFF)
- `module/frontdesk/controller/dto/RecallDueDTO.java` (contactId, name, lastVisitAt, daysSinceLastVisit,
  nudgedThisPeriod)
- `module/frontdesk/controller/dto/CallbackInboxItemDTO.java` (activityId, contactId, callerName,
  callbackPhone, intentBucket, callbackRequested, receivedAt — logistics-only, no transcript; F2)

### Touched (additive only)
- `module/frontdesk/automation/RecallLogRepository.java` (+`findByTenantIdAndPeriodKey` derived finder —
  backs the `nudgedThisPeriod` enrichment; the recall job runs outside a request context so the explicit
  `tenantId` predicate is required)
- `repository/ActivityRepository.java` (+`findAllByTenantIdAndTypeAndDirectionAndBodyOrderByOccurredAtDesc`
  derived finder — the F2-marker callback query)
- `integration/twilio/voice/TwilioVoicemailService.java` (`TRANSCRIPT_REDACTED_MARKER` promoted
  package-private → `public` so the controller references the same constant — value byte-unchanged)
- `controller/advice/GlobalErrorHandler.java` (+4295-4299 doc band — *see below; appended*)

### Reused, NOT copied / NOT changed
- The FD-2 lapsed-contact selector logic (replicated in the read because the job's method is private and
  returns ids only), `RecallLog`/`RecallLogRepository`, `AppointmentRepository.findAllByTenantId`,
  `ContactRepository.findByTenantIdAndId`, `ActivityRepository`, `TenantModuleRegistry`, `RoleGuard`.

### Tests
- `src/test/java/.../module/frontdesk/FrontDeskBoardReadIT.java` — (1) `GET /frontdesk/recall` returns
  ONLY the lapsed contact (last visit 200d ago, no upcoming) — not the upcoming-appointment contact, not
  the recently-seen contact — with the resolved name, days-since, and `nudgedThisPeriod=true`; (2) `GET
  /frontdesk/callbacks` returns the F2-redacted health callback with its logistics fields (name /
  callbackPhone=555-0142 / intentBucket=PRESCRIPTION_REFILL_REQUEST) and **EXCLUDES** a non-redacted mole
  inbound-call Activity that stores a transcript, with the **F2 assertion** (the serialized response
  contains no clinical token and no transcript/body/recording field); (3) a non-frontdesk tenant → 1132
  (both reads); (4) a non-staff role → 1800 (both reads). (`WebTestClient` + JWT, the `NoShowRiskController`
  pattern; pure Mongo-seeded, no WireMock/Twilio.)

## Validation status

- `./gradlew compileJava compileTestJava` — GREEN.
- `./gradlew cleanTest test --tests "*FrontDeskBoard*IT" --tests "*FrontDeskConfirmationIT"
  --tests "*HealthFrontDeskVoicemailIT" --tests "*FrontDeskNoShowScoringServiceIT"
  --tests "*OpenApiEndpointIT"` — **GREEN** (force-clean). Per-class (tests/failures/errors):
  **FrontDeskBoardReadIT 4/0/0**; HealthFrontDeskVoicemailIT (FD-3 F2 regression) 2/0/0;
  FrontDeskConfirmationIT (FD-2 regression) 8/0/0; FrontDeskNoShowScoringServiceIT (FD-1 regression)
  10/0/0; OpenApiEndpointIT 2/0/0. (26 total, 0 failures.)

## Hard gates

1. **The callback read NEVER exposes a transcript (F2)** — the `CallbackInboxItemDTO` has no
   transcript/body/recording field; the read filters to the F2-marker activities and projects only the
   logistics `extractedJson` + caller-ID. The IT asserts the serialized `/frontdesk/callbacks` response
   contains no clinical token (and the `transcript`/`recordingUrl`/`recordingSid` field names) AND that
   the non-redacted mole transcript Activity is excluded entirely.
2. **Module-gated, blast radius zero** — `@ConditionalOnProperty(frontdesk)` (default off) +
   `requireEnabled` per handler + STAFF `RoleGuard`. The controller is **absent from the OpenAPI spec**
   when off: OpenApiEndpointIT green; both the live-generated spec and committed `docs/api/openapi.json`
   contain no `/frontdesk/recall` or `/frontdesk/callbacks` path; `git status` confirms `openapi.json`
   unchanged.
3. **FD-1..FD-4 / ChairFill / NMM untouched** — FD-1 (10/0/0), FD-2 (8/0/0), FD-3 F2 (2/0/0) ITs pass
   unchanged. FD-5a is a purely additive read controller + 2 DTOs + 2 additive repo finders + a
   visibility-only constant promotion; it touches no FD-1..FD-4 / voicemail-pipeline / scoring behavior.
4. **Error band 4295-4299 reserved** — FD-5a mints NO new error code (reuses 1130/1132 module gate, 1800
   STAFF). The 4295-4299 band is reserved + documented for future board-read growth.

## Deviations / surprises

- **The FD-2 lapsed selector is replicated in the read, not extracted/shared.** `RecallDetectorJob`'s
  `lapsedContacts(...)` is a private method that returns contact ids only; the read needs the per-contact
  last-visit timestamp (for `lastVisitAt` / `daysSinceLastVisit`). Replicating the small, well-tested
  computation (keeping the timestamp) was cleaner + lower-blast-radius than refactoring the job's private
  method out into a shared selector (which would touch shipped FD-2 code). Both use the SAME window config,
  so they agree on who is lapsed.
- **The F2 discriminator is the redaction-marker body, not a new field.** Rather than add a vertical tag to
  `Activity`, the read keys on `body == TRANSCRIPT_REDACTED_MARKER` — which is produced *only* by the
  health strategy. This is the cleanest tenant-scoped query that excludes mole/multi-trade voicemails (they
  store the transcript as body) with zero model change. The IT proves the exclusion with a seeded mole
  transcript Activity.
- **`TRANSCRIPT_REDACTED_MARKER` promoted to `public`.** It was package-private `static final` on
  `TwilioVoicemailService`; the controller (a different package) references it so the read and the writer
  share one source of truth for the marker. Value byte-unchanged; the FD-3 IT (which used a string literal)
  is unaffected.
- **`callbackPhone` falls back to the caller-ID `From`.** The extracted `callbackNumber` can be null (the
  caller didn't state one); the Twilio caller-ID is captured independently, so the inbox row is always
  dialable (the FD-3 "a callback is never dropped" posture, carried into the read).
