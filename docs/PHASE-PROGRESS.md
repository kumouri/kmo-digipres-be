# PHASE-PROGRESS — E1 Nurture / Cadence Engine (`nurture-cadence-engine`)

> Fresh ledger for this branch (off `main` @ f245547). Supersedes the prior FD-1 ledger that
> occupied this path — that work is already on `main`.

Detail plan: `~/.claude/plans/nurture-cadence-engine.md`.
Error band **4300-4319**. Module key `nurture` (admin/CRUD controller, `matchIfMissing=true`); the
scheduled runner has its OWN gate `kmosf.modules.nurture-runner` (`matchIfMissing=false`,
**default-OFF** — the GBP poller/admin split precedent).

**Extend-vs-sibling decision: SIBLING.** Strictly-additive `…/nurture/…` package. `SequenceEngine`,
`model/sequence/Sequence*`, `SequenceStepType`, `WorkflowRule`/`RuleEngine`, `InboundSmsService`,
`TwilioSmsService`, `EmailService`, `AnthropicAiAssistService` all MUST be empty-diff vs `main`
(verify `git diff main -- <file>` = 0 lines). Rationale in the detail plan §0.

| Sub-phase | Scope | Status | Validating IT | Commit |
|---|---|---|---|---|
| N0 | Detail plan + this ledger | DONE | — | (this commit) |
| N1 | Model (`NurtureCampaign`/`NurtureEnrollment`/`NurtureSendLog` + enums/embedded) + 3 repositories | DONE (compiles) | compile + `OpenApiEndpointIT` boot | (N1 commit) |
| N2 | `NurtureSegmentationService` + `NurtureAutoConfiguration` (ModuleDefinition) + `AutoConfiguration.imports` entry + `DomainEventType` Nurture block | DONE | `NurtureSegmentationIT` (3/3 green) | (N2 commit) |
| N3 | `NurtureMessageComposer` + `NurtureRunner` (default-OFF) | TODO | `NurtureRunnerIT` | |
| N4 | `NurtureReplyService` (reply→exit→book) | TODO | `NurtureReplyBookIT` | |
| N5 | `NurtureAnalyticsService` | TODO | `NurtureAnalyticsIT` | |
| N6 | `NurtureCampaignController` + DTOs + `GlobalErrorHandler` 4300-4319 Javadoc + openapi regen | TODO | `NurtureCampaignControllerIT` + `OpenApiEndpointIT` | |

## Invariants (must hold at every sub-phase)
- **Reactive:** no `.block()` on the Netty loop; the scheduled tick subscribes on the scheduler
  thread (the `CoverageNudgeJob`/`SequenceEngine` pattern); blocking work on
  `Schedulers.boundedElastic()`. `@Bean` (not `@Component`) where the conditional pattern requires.
- **`switchIfEmpty` only for genuine not-found** (4301 campaign / 4310 enrollment). Every
  find-or-enroll + the per-(enrollment, step) send is **explicit-boolean probe + ledger-insert-FIRST**
  over a unique index with `DuplicateKeyException → Mono.empty()`. NEVER `switchIfEmpty(create/send)`.
- **Runner default-OFF** (`kmosf.modules.nurture-runner.enabled` matchIfMissing=false) → no live
  sends in CI / any default run.
- **No live external in the loop:** Anthropic → WireMock (`@DynamicPropertySource(kmosf.ai.anthropic.base-url)`);
  Twilio/Email → the precedented `@MockitoBean` send seams; no host/key/charge/send hardcoded.
- **TCPA:** skip `sms-opt-out`-tagged contacts (`RiskTieredPreventionService.SMS_OPT_OUT_TAG`); honor
  the per-campaign rolling frequency cap.

## Validation log
- N0: detail plan + this fresh ledger committed; early push + draft PR for the hedge.
