# PHASE-PROGRESS — E2 Inbound Responder + Intent Router (`responder-intent-router`)

> Fresh ledger for this branch (off `main` @ `6b04ce0`, post-E1-nurture merge). Replaces the prior
> E1-nurture ledger that occupied this path — that work is already on `main`.
>
> Detail plan: `~/.claude/plans/responder-intent-router.md`. Error band **4320–4339**. Module gate
> `kmosf.modules.responder` (matchIfMissing=true).
>
> **The acceptance bar is the regression:** the in-use inbound-SMS ITs
> (`GapFillWaitlistIT`, `RealEstateConciergeIT`) MUST stay green; the ONLY change to
> `InboundSmsService` is the additive `IGNORED`-fallthrough delegation to the new
> `InboundIntentRouter`.

## Sub-phase ledger

| Sub-phase | Scope | Commit | Status |
|---|---|---|---|
| E2.0 | Detail plan + fresh ledger | 40827dd | DONE |
| E2.1 | model + repos + DomainEventType block + config props | b0f5eda | DONE |
| E2.2 | `InboundIntentClassifier` (VoicemailExtractionService clone, text) | 831b1d6 | DONE |
| E2.3 | `ConversationStateService` + `IntentHandler` + `DefaultHandoffIntentHandler` + `InboundIntentRouter` | 8358baf | DONE |
| E2.4 | surgical `InboundSmsService` delegation + `InboundOutcome.RESPONDER_HANDLED` | cd09875 | DONE |
| E2.5 | `ResponderAutoConfiguration` + condition + `@ConditionalOnMissingBean` fallback + wiring bean | 76124e2 | DONE |
| E2.6 | `ResponderConfigController` + `GlobalErrorHandler` 4320-4339 Javadoc | 2a86c8c | DONE |
| E2.7 | ITs (25 tests) + `verifyOpenApi` regen + commit `docs/api/openapi.json` | c7c3234 | DONE |

## Validation log (all GREEN, local Docker/Testcontainers)
- REGRESSION gate — `./gradlew test --tests "*GapFillWaitlistIT" --tests "*RealEstateConciergeIT"`:
  `GapFillWaitlistIT` **11/0/0/0**, `RealEstateConciergeIT` **5/0/0/0** — pass UNCHANGED with the seam wired.
- New — `./gradlew test --tests "com.kumouri.kmodigipresbe.module.responder.*"`:
  `InboundIntentClassifierIT` **8/0/0/0**, `InboundIntentRouterIT` **7/0/0/0**,
  `ConversationStateIT` **3/0/0/0**, `ResponderConfigIT` **7/0/0/0** = **25/0/0/0**.
- Boot guard — `OpenApiEndpointIT` **2/0/0/0** (full context boots with the responder wiring active).
- `verifyOpenApi` refreshed `docs/api/openapi.json` (adds `/responder/config[/test-classify]` +
  the now-default-registered `/public/integrations/twilio/{tenantId}/sms`).

## Key invariants for this branch (carry-forward)
- **`switchIfEmpty` only for genuine not-found.** find-or-create (conversation, config) is
  explicit-boolean; the only new-package `switchIfEmpty` is the classifier's genuine house-key
  fallback (the verbatim `VoicemailExtractionService` pattern).
- **No-config = no-op = `IGNORED`** — default-tenant behavior unchanged (dedicated IT).
- **No live external** — Anthropic → WireMock; Twilio/Email send → `@MockitoBean`; sandbox fakes.
- **Empty-diff** — `TwilioSmsService`, `WaitlistClaimService`, `ConciergeInboundRouter`,
  `AnthropicAiAssistService`, `AiVisionService` (verify `git diff main`, 0 lines each). The only
  pre-existing files touched: `InboundSmsService` (surgical seam), `InboundSmsModuleEnabledCondition`
  (one additive nested condition), `GlobalErrorHandler` (Javadoc), `DomainEventType` (additive block),
  `application.properties` (additive keys), `docs/api/openapi.json` (regen).

## Deviations / notes
- (none yet)
