# Phase H — External-Integrations-Glue — Progress Ledger

> Crash-recovery source of truth per ultraplan §6 Resilience. Each sub-phase is its
> own commit; this row is set →in-progress (committed) as the first action and
> →done+results (committed) as the last action of every sub-phase. Plan §10 is a
> one-line pointer here. Plan: back-office-kmo-digipres-phase-H-external-integrations-glue.md

## Branch: back-office-kmo-digipres-phase-H-external-integrations-glue  (base main @ 24f7e5e)
## Model: Plan=Opus 4.7 (orchestrator-direct) · Implement=Sonnet 4.6 · Validate/final=Opus 4.7 (external-ingress trust + auth-regression focus)

| Sub-phase | Status | SHA | Build (main base / +H / skip) | Mandated checks | Deviations |
|---|---|---|---|---|---|
| H.1 error range 3900-3999 + DomainEventType Phase-H block | in-progress |  |  | no error-code collision; advisory events compile | |
| H.2 Cal.com webhook + client + Meeting source-of-truth projection | todo |  |  | switchIfEmpty grep; verify-before-effect + tenant-from-path; Meeting/MeetingCrudService/BookingService empty/additive diff | |
| H.3 IMAP inbound poll-source → unchanged InboundEmailService | todo |  |  | switchIfEmpty grep; default-OFF; Message-ID idempotent; InboundEmailService empty diff | |
| H.4 Postmark bounce/spam routing (in-place) | todo |  |  | switchIfEmpty grep; PostmarkAuthVerifier empty diff; reuse EMAIL_BOUNCED/EMAIL_SPAM, no new event/controller | |
| H.5 Activepieces webhook-subscription seed | todo |  |  | switchIfEmpty grep (explicit-boolean seed idempotency); WebhookDeliveryService/WebhookSubscription empty diff; R8 read-only token | |
| H.6 opt-in portal Zitadel oauth2Login (reuse A2) | todo |  |  | non-opted local path byte-identical; A2 resolver/role-map reused verbatim; MagicLinkRedeemIT/PortalChainIT/portal ITs green | |
| H.7 BE ITs AC-H1/H2/H3/H4/H5 (WireMock/stub, shard-safe) | todo |  |  | AC covered; unsigned-reject + duplicate-200-no-op + cross-tenant negatives; shard-safe (no @MockBean); no main regression | |
| H.8 docs in-PR + openapi.json + final BE green + PR | todo |  |  | spec has new ingress paths; Opus validation sign-off | |

## No-live-external boundary (§7) — recorded
- [ ] Cal.com/IMAP/Mailcow/Activepieces/Postmark/Zitadel = WireMock/sandbox/stub/.invalid-default, configurable base URLs, default-OFF pollers; no host hardcoded, no live token/charge/deployment. Live = a separate human action, never the loop.

## External-ingress trust (the §9 #1 invariant) — recorded
- [ ] Every new webhook: verify signature/auth BEFORE any effect; tenant from URL path NEVER payload; ledger-insert-FIRST + unique tenant_event_idx + DuplicateKeyException→Mono.empty(); synthetic TenantContext(...,INTEGRATION_*); duplicate redelivery → 200 no-op zero second effect.
- [ ] switchIfEmpty over each new integration/<provider>/ = genuine not-found / 2510 only; ZERO switchIfEmpty(process/create).
- [ ] Reused cores empty/additive diff: InboundEmailService / WebhookDeliveryService / WebhookSubscription / PostmarkAuthVerifier / A2 JwtTenantResolver+ZitadelClaimTenantResolver+ZitadelOrgTenantCache / MeetingCrudService / BookingService / StripeWebhookService / DocumensoWebhookService / PortalSecurityConfig(local) / MagicLinkService.buildLink.
- [ ] Portal Zitadel federation opt-in per Tenant.zitadelOrgId; non-opted path byte-identical.

## Validator (Opus 4.7, separate agent) sign-off — external-ingress trust + auth-regression focus
BE [ ] — switchIfEmpty grep each new integration/* (genuine not-found/2510 only): [ ] ·
verify-before-effect + tenant-from-URL-path + ledger-insert-first + unique idx + concurrent DuplicateKey→empty (Cal.com/Postmark): [ ] ·
reused cores empty/additive diff (the list above): [ ] ·
Cal.com source-of-truth: Meeting projection additive, MeetingCrudService/BookingService empty diff, no double-emit: [ ] ·
IMAP default-OFF + Message-ID idempotent + InboundEmailService empty diff: [ ] ·
Activepieces seed explicit-boolean idempotent + WebhookDeliveryService empty diff + R8 read-only token: [ ] ·
portal Zitadel opt-in; non-opted local path byte-identical; A2 reused verbatim; MagicLinkService.buildLink unchanged; portal/MagicLink/PortalChain ITs green: [ ] ·
no live external (WireMock/stub/.invalid, default-OFF, no host/token hardcoded): [ ] ·
shard-safe (no @MockBean, no extra prop source, self-clean); new-resource-client lifecycle (none / shared builder / per-cycle close): [ ] ·
docs/openapi in-PR; webhook controllers no @ConditionalOnProperty; ActivityType unchanged: [ ]

## PHASE H COMPLETE — <date>. BE PR #__ merge-commit <sha>. main carries the external-integrations-glue surface
(Cal.com source-of-truth projection; IMAP poll-source default-OFF; Postmark bounce in-place; Activepieces webhook-subscription seed; opt-in portal Zitadel; all WireMock/sandbox — no live external). **Track-1 loop PAUSES here: Phase I is deferred (ultraplan D13) until the first paying client is funded — report, do not start.**
