# Phase F — Contracts & Documenso — Progress Ledger

> Crash-recovery source of truth per ultraplan §6 Resilience. Each sub-phase is its
> own commit; this row is set →in-progress (committed) as the first action and
> →done+results (committed) as the last action of every sub-phase. Plan §10 is a
> one-line pointer here. Plan: back-office-kmo-digipres-phase-F-contracts-documenso.md

## Branch: back-office-kmo-digipres-phase-F-contracts-documenso  (base main @ e5f3fb9)
## Model: Plan=Opus 4.7 · Implement=Sonnet 4.6 · Validate/final=Opus 4.7 (HMAC/legal-signature focus)

| Sub-phase | Status | SHA | Build (main base / +F / skip) | Mandated checks | Deviations |
|---|---|---|---|---|---|
| F.1 error range 3700-3799 + DomainEventType Phase-F + contracts module prop | done | 9b5c937 | main base 427/0/0 ; +0 Phase-F ; 0 skip (total 428/0/0/0) | no error-code collision; compileJava clean; full test green | none |
| F.2 DocumensoProperties/Config + FileStorageService.putBytes (S3AsyncClient, no new dep) | in-progress |  |  | presign paths unregressed | |
| F.3 Contract + ContractTemplate + DocumensoWebhookEvent + repos | todo |  |  | indexes auto-create; DocumensoWebhookEvent NOT Auditable | |
| F.4 ContractPdfService + ContractTemplateService + ContractService (explicit-boolean SOW-from-quote) | todo |  |  | switchIfEmpty grep (genuine not-found only) | |
| F.5 DocumensoClient send + ContractNumberGenerator + /send + controllers | todo |  |  | switchIfEmpty grep; no Documenso host/live token | |
| F.6 DocumensoSignatureVerifier + Adapter + DocumensoWebhookService + controller (HMAC headline) | todo |  |  | switchIfEmpty grep; ledger-insert-FIRST; tenant-from-path; promotion reuses existing path | |
| F.7 BE ITs AC-F1/F2/F3 (Documenso WireMock, signed→S3, exactly-once promo, 3710) | todo |  |  | AC-F1/F2/F3 covered; no main regression | |
| F.8 CLAUDE.md in-PR + .claude/* local + docs/api/openapi.json committed | todo |  |  | spec has Contract/ContractTemplate + new paths | |
| F.9 final BE green + PR | todo |  |  | Opus validation sign-off | |

## Documenso-payload-format ASSUMPTION (the ultraplan's called-out known unknown — F-D7)
- Signature header (assumed): `X-Documenso-Signature` — referenced ONLY in DocumensoWebhookController + DocumensoSignatureVerifier.
- Digest scheme (assumed): HMAC-SHA256 over the raw request body, hex, constant-time vs the header — DocumensoSignatureVerifier is the ONLY place it lives.
- Payload shape (assumed): {id|eventId, event|type ∈ {document.completed,document.signed}, payload:{documentId, downloadUrl?}} — DocumensoEventAdapter.parse is the ONLY place it is assumed; missing downloadUrl falls back to DocumensoClient.downloadSignedPdf(documentId).
- Correcting against a real Documenso deployment = a one-method change in each of those (≤3 files). The WireMock stub in DocumensoWebhookSignedIT/DocumensoSendWireMockIT is the single contract coded-to.

## No-live-Documenso boundary (§7) — recorded
- [ ] Every Documenso interaction WireMock/sandbox; base URL configurable (IntegrationConnection.config[apiBaseUrl] → DocumensoProperties), pointed at WireMock in all tests; no host hardcoded; apiToken a sandbox fake; webhook secret a test secret. Live deployment = a separate human action, never the loop.

## Validator (Opus 4.7, separate agent) sign-off — HMAC/legal-signature focus
BE [ ] — switchIfEmpty grep over service/contract/+integration/documenso/ (every hit genuine 3705/3707/3712/3716): [ ] ·
DocumensoWebhookService mirrors StripeWebhookService (constant-time HMAC; explicit-boolean event-id probe; ledger-insert-FIRST; duplicate=200-no-op; signature-fail=stable 3710): [ ] ·
tenant resolved from path→IntegrationConnection, never payload: [ ] ·
signed PDF stored via putBytes exactly-as-served, not re-rendered, renderedPdfStorageRef≠signedPdfStorageRef, presign paths unregressed: [ ] ·
SOW→WON+Project = reuse of moveStage+convertFromDeal (no parallel path; DealCrudService/ProjectService empty diff vs main; exactly-once via !promotedDealToWon + existsByTenantIdAndDealId): [ ] ·
Documenso-payload assumption contained to Adapter+Verifier+controller-header & called out (CLAUDE.md/PR): [ ] ·
no live Documenso (no host hardcoded, no live token in any fixture, WireMock only): [ ] ·
Contract/ContractTemplate Auditable, DocumensoWebhookEvent NOT Auditable; partial-unique tenant_number_idx correct: [ ]

## PHASE F COMPLETE — <date>. BE PR #__ merge-commit <sha>. main carries the contracts vertical
(Documenso WireMock/sandbox only — a real deployment remains a separate human action). Next: Phase G / Phase H (Activepieces glue).
