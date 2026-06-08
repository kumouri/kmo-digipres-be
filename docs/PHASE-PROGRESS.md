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
