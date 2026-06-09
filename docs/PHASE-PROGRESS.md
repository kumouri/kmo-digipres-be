# PHASE-PROGRESS — T3 "Real Estate Midnight Responder" (`re-midnight-responder`)

> Fresh ledger for this branch (off `main` @ `06eadff`). Replaces the prior `health-revenuerevive` (T2)
> ledger that occupied this path — that work is already on `main`.

Branch `re-midnight-responder`. Error band **4380-4389**. Module gate: BOTH `kmosf.modules.realestate`
AND `kmosf.modules.responder`. Detail plan: `~/.claude/plans/re-midnight-responder.md`.

T3 is the **routing + completeness layer** over the shipped RE concierge (RE-1..RE-5) + T1 (RE nurture) +
E2 (responder). It does NOT rebuild RAG / qualification / scoring / booking / nurture. Four pieces:
tier-routing on qualification (WARM→nurture, COLD→long-cadence, HOT→existing hot-handoff), off-listing
HANDOFF→E2-responder delegation, response-latency instrumentation, and the demo seed.

## Sub-phase ledger

| Sub | Scope | State |
|---|---|---|
| T3.0 | Detail plan + this ledger (first commit) | DONE |
| T3.1 | Per-tenant config doc + repo + CRUD controller + DTO (`MidnightResponderConfig`, 4380/4381) | DONE |
| T3.2 | Tier routing — `TierRoutingService` (`LEAD_SCORE_UPDATED` WARM/COLD direct-enroll; HOT no-op) + `CONCIERGE_TIER_ROUTED` event + the both-module auto-config | DONE |
| T3.3 | Off-listing delegation — `ResponderHandoffDelegate` seam on `ConciergeInboundRouter` + impl over E2 `DefaultHandoffIntentHandler` + wiring | DONE |
| T3.4 | Latency instrumentation — additive `ConciergeTurn.receivedAt`/`latencyMs` + the stats read endpoint | DONE |
| T3.5 | Demo seed — `MidnightResponderDemoSeeder` (`@Profile("demo-realestate-responder")`) | DONE |
| T3.6 | Tests — new T3 ITs + ALL regression groups green | DONE |
| T3.7 | Docs — `CLAUDE.md` T3 entry + `GlobalErrorHandler` 4380-4389 `<li>` + openapi verify | DONE |

## Empty-diff cores (verified vs `main` — see the PR report)
`ConciergeAnswerService`, `ListingConciergeService`, `RagRetrievalService`/`AskAiService`,
`LeadScoringV2Service`, `LeadHandoffService`, `QualificationService`, `NurtureRunner`/
`NurtureSegmentationService`/`NurtureReplyService`/`NurtureMessageComposer`, `InboundSmsService`,
`TwilioSmsService`, `InboundIntentRouter`, `DefaultHandoffIntentHandler`, `RealEstateAutoConfiguration`,
`RealEstateNurtureAutoConfiguration`, `ResponderAutoConfiguration`.

## Additive seams (justified — NOT empty-diff)
- `ConciergeInboundRouter` — `@Nullable ResponderHandoffDelegate` + `setResponderHandoff(...)` + 2 call-sites
  (NO_LISTING always-if-wired; HANDOFF if the per-tenant config flag) + `receivedAt`/`latencyMs` stamping.
  Null delegate ⇒ byte-identical RE-1/RE-2/RE-3 (the RE regression ITs prove it). `ConciergeInboundRouter`
  is the realestate routing owner (T1 doc), NOT a shared core — the setter-injection precedent
  (`setConciergeRouter`/`setIntentRouter`/`setCopyFilter`).
- `ConciergeTurn` — additive nullable `receivedAt` + `latencyMs` (legacy turns deserialize null).
- `DomainEventType` — `CONCIERGE_TIER_ROUTED` (advisory).
- `GlobalErrorHandler` — the 4380-4389 Javadoc `<li>`.

## §9 invariants held
Tier-route enroll is explicit-boolean over the E1 `tenant_campaign_contact_idx` unique index
(`findBy…().map(true).defaultIfEmpty(false)` + `onErrorResume(DuplicateKeyException→false)`) — never
`switchIfEmpty(create)`. No `.block()` in production. No live external (Anthropic/OpenAI→WireMock,
Twilio→`@MockitoBean`). Every webhook-reachable path is best-effort (the tier router + the delegate swallow
errors to a no-op) — never 500 a webhook.

## Validation (this branch)
- **All 5 new T3 ITs green:** `MidnightResponderTierRoutingIT` (7), `MidnightResponderConfigIT` (4),
  `MidnightResponderModuleGateIT` (3), `MidnightResponderLatencyIT` (2),
  `MidnightResponderHandoffDelegationIT` (2 — both nested contexts).
- **All regression groups green:** the 5 RE concierge ITs, `module.realestate.nurture.*`, `nurture.*` (E1),
  `module.responder.*`, `OpenApiEndpointIT`.
- **openapi.json no-diff** (the T3 controllers are realestate-gated; the spec boots without that flag → FE
  hand-writes its client).
- **Empty-diff verified** for all 16 named cores + `RagRetrievalService`/`AskAiService`/`LeadScoringV2Service`.
- **Test-URI lesson:** `@AutoConfigureWebTestClient`+RANDOM_PORT binds at the declared controller path
  WITHOUT the `spring.webflux.base-path` (`/api/v1`) prefix (the `RealEstateConciergeConversationReadIT`
  precedent). T3 ITs hit `/realestate/responder/...`, NOT `/api/v1/...` (a prepended prefix 404s to the
  static-resource handler).
