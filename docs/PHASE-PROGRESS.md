# Phase 1 (NMM AI intake) — Voicemail-to-Lead Pipeline — Progress Ledger

> Crash-recovery source of truth. Each sub-phase is its own commit; this row is set
> →in-progress (committed) as the first action and →done+results (committed) as the
> last action of every sub-phase. Plan: `i-had-an-idea-ticklish-rivest.md` §2/§3/§5/§6
> (the NMM AI intake layer; Feature A — voicemail-to-lead). The pipeline is a faithful
> structural mirror of the shipped Cal.com webhook (Phase H — `CalComWebhookService`).

## Branch: nmm-ai-intake-phase-1-voicemail-to-lead  (base main @ 2b24df8)
## Model: Implement=Opus 4.8 (1M context). BE-only — no FE this phase. No live external services (§7).

| Sub-phase | Status | SHA | Build (compileJava+compileTestJava) | Mandated checks | Deviations |
|---|---|---|---|---|---|
| 1.1 error range 4000-4099 + DomainEventType Phase-1 block + ledger entity/repo + additive Contact phone finder + config props | done | (this commit) | BUILD SUCCESSFUL true-exit 0 (9s) | error-code block 4000-4099 added to GlobalErrorHandler Javadoc only (no handler code touched, no existing `<li>` modified); DomainEventType +2 advisory constants (VOICEMAIL_RECEIVED/VOICEMAIL_LEAD_CREATED) before private ctor; TwilioVoicemailEvent = TenantScoped NOT Auditable (CalComWebhookEvent mirror), unique compound tenant_callsid_idx {tenantId,callSid}; TwilioVoicemailEventRepository.findByTenantIdAndCallSid (explicit-boolean probe finder, explicit tenantId predicate); ContactRepository.findByTenantAndPhoneNumber = strictly-additive @Query finder (existing email finder + ContactCrudService unchanged); config props additive (module gate matchIfMissing=true, greeting/auto-ack/extraction-model, anthropic base-url surfaced) | none |
| 1.2 TwilioRequestValidator + voice/voicemail controllers + TwilioVoicemailService skeleton (verify→idempotency→ledger-first→synthetic ctx) | pending | — | — | — | — |
| 1.3 VoicemailExtractionService (Anthropic, mirrors AnthropicAiAssistService) + VoicemailTranscription seam | pending | — | — | — | — |
| 1.4 lead (Contact find-or-create) + Activity(CALL,INBOUND) + notify Rob + auto-ack caller (best-effort) | pending | — | — | — | — |
| 1.5 BE ITs (signed callback / invalid-sig / duplicate CallSid / not-connected / TwiML voice) + TwilioRequestValidator unit test | pending | — | — | — | — |
| 1.6 docs in-PR (CLAUDE.md Phase-1 section + openapi.json) + final BE green + PR | pending | — | — | — | — |

## No-live-external boundary (§7) — to verify
- [ ] Twilio + Anthropic base URLs configurable and pointed at WireMock in every test; auth token / api key are sandbox fakes; NO host hardcoded; NO live call/charge/send anywhere. Provisioning a real Twilio number + call-forwarding is a SEPARATE HUMAN ACTION — never in this code/loop.

## External-ingress trust (the §9 #1 invariant) — to verify
- [ ] verify-before-effect (X-Twilio-Signature via TwilioRequestValidator) precedes any effect; tenant from URL path NEVER payload; ledger-insert-FIRST (TwilioVoicemailEvent.save) under unique tenant_callsid_idx + DuplicateKeyException→Mono.empty(); synthetic TenantContext(...,INTEGRATION_TWILIO); duplicate redelivery → 200 no-op zero second effect.
- [ ] switchIfEmpty over the new package = genuine not-found / 4001 only; ZERO switchIfEmpty(process/create) on the idempotency seam.
- [ ] Reused cores empty/additive diff: AnthropicAiAssistService / TwilioSmsService / EmailService / ContactCrudService / ActivityCrudService / IntegrationConnection*.
