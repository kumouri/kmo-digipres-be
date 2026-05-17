# Phase F — Contracts & Documenso — Progress Ledger

> Crash-recovery source of truth per ultraplan §6 Resilience. Each sub-phase is its
> own commit; this row is set →in-progress (committed) as the first action and
> →done+results (committed) as the last action of every sub-phase. Plan §10 is a
> one-line pointer here. Plan: back-office-kmo-digipres-phase-F-contracts-documenso.md

## Branch: back-office-kmo-digipres-phase-F-contracts-documenso  (base main @ e5f3fb9)
## Model: Plan=Opus 4.7 · Implement=Sonnet 4.6 · Validate/final=Opus 4.7 (HMAC/legal-signature focus)

| Sub-phase | Status | SHA | Build (main base / +F / skip) | Mandated checks | Deviations |
|---|---|---|---|---|---|
| F.1 error range 3700-3799 + DomainEventType Phase-F + contracts module prop | done | 9b5c937 | crashed-agent self-report "428/0/0/0 full green" — UNVERIFIED by orchestrator (not trusted per §6). Orchestrator-verified: compileJava clean; additive-only (Javadoc + event constants + 1 property). Full-suite no-regression → CI at PR (§6 scale gate) | no error-code collision (verified by inspection); compileJava clean (verified) | none |
| F.2 DocumensoProperties/Config + FileStorageService.putBytes (S3AsyncClient, no new dep) | done | b5c9abe | crashed-agent self-report "428 green" — UNVERIFIED. Orchestrator-verified: compileJava+compileTestJava clean; full Spring context boots WITH DocumensoConfig + S3FileStorageService (proven by ContractEntityIndexIT @SpringBootTest green); putBytes is additive (interface + impl), presign methods untouched | presign methods unchanged (verified by inspection — additive interface method); S3AsyncClient in already-present aws sdk:s3 (no new dep, verified build.gradle) | none |
| F.3 Contract + ContractTemplate + DocumensoWebhookEvent + repos | done | 179bcf1 | F.3 gate (orchestrator-verified): compileJava+compileTestJava clean (exit 0); ContractEntityIndexIT 9/9 green (BUILD SUCCESSFUL 55s, full Spring context). Full-suite Chunk-1 local re-validation INCONCLUSIVE (25-min `timeout` kill, exit 124 — local env limitation; 0 failures/0 errors in the partial run; NOT a test failure) → authoritative no-regression is CI at PR per §6 | indexes auto-create (verified by ContractEntityIndexIT); Contract/ContractTemplate Auditable, DocumensoWebhookEvent TenantScoped NOT Auditable (verified) | **F-D10 (necessary correction):** plan said declare partial-unique `tenant_number_idx` on `@CompoundIndex` (greenfield ⇒ no initializer). Spring Data `@CompoundIndex` CANNOT express `partialFilterExpression` (literal E.11 root cause; confirmed by `InvoiceNumberIndexInitializer` Javadoc). Applied the proven `InvoiceNumberIndexInitializer` pattern via new `scheduling/ContractNumberIndexInitializer` (key-only `@CompoundIndex` + initializer owns partial-unique). F-D10 premise infeasible; only working approach. Validator: confirm the initializer faithfully mirrors `InvoiceNumberIndexInitializer`. |
| F.4 ContractPdfService + ContractTemplateService + ContractService (explicit-boolean SOW-from-quote) | in-progress |  |  | switchIfEmpty grep (genuine not-found only) | |
| F.5 DocumensoClient send + ContractNumberGenerator + /send + controllers | todo |  |  | switchIfEmpty grep; no Documenso host/live token | |
| F.6 DocumensoSignatureVerifier + Adapter + DocumensoWebhookService + controller (HMAC headline) | todo |  |  | switchIfEmpty grep; ledger-insert-FIRST; tenant-from-path; promotion reuses existing path | |
| F.7 BE ITs AC-F1/F2/F3 (Documenso WireMock, signed→S3, exactly-once promo, 3710) | todo |  |  | AC-F1/F2/F3 covered; no main regression | |
| F.8 CLAUDE.md in-PR + .claude/* local + docs/api/openapi.json committed | todo |  |  | spec has Contract/ContractTemplate + new paths | |
| F.9 final BE green + PR | todo |  |  | Opus validation sign-off | |

## RECOVERY LOG (ultraplan §6 Resilience — crash-recovery source of truth)
- **2026-05-17 — Chunk-1 implementer crash, orchestrator-direct recovery.** The Sonnet Chunk-1 (F.1–F.3) agent returned a **non-contract report** ("Good. All F.3 files are untracked and ready to stage. Waiting for the test results.") after ~40 min / 116 tool calls — a suspected crash per the §6 no-report⇒assume-crash rule. The orchestrator did NOT trust the summary; reconstructed state from `git log` + this ledger:
  - F.1 (`9b5c937`) + F.2 (`b5c9abe`) committed + pushed + ledger-closed by the agent (forward-only discipline held — all 5 pre-crash commits pushed, `origin` in sync).
  - F.3 was uncommitted WIP (entities/repos/initializer/IT untracked) + the unplanned `ContractNumberIndexInitializer` — classified **build-on** after a full conformance review vs F-D2/F-D3 + the proven `InvoiceNumberIndexInitializer` (the deviation is the necessary F-D10 correction above).
  - Re-validated independently: `compileJava+compileTestJava` exit 0 (F.1+F.2 committed + F.3 WIP); cleared a transient Windows build-lock (orphaned gradle JVMs from the crashed agent's in-flight test run, surgically killed by PID — VS Code LSP + unrelated JVMs untouched); `ContractEntityIndexIT` 9/9 green on a clean process space.
  - Orchestrator-direct finalization (small/high-judgment remainder per the runbook): committed F.3 (`179bcf1`) + this ledger close. Friction logged per §6 Learn.
  - **Authoritative re-validation outcome:** the crashed agent's self-reported "F.1/F.2 full-suite 428/0/0/0" is NOT trusted. Orchestrator verified: `compileJava`+`compileTestJava` clean (F.1+F.2+F.3); `ContractEntityIndexIT` `@SpringBootTest` 9/9 green ⇒ the full Spring context boots with F.1's props + F.2's `DocumensoConfig`/`S3FileStorageService` + the 3 new entities/indexes (no context/bean regression); F.1/F.2 are additive-only (Javadoc + event constants + 1 property + an additive interface method). A full local `./gradlew test` was attempted as the Chunk-1 close gate but **timed out at the 25-min `timeout` wrapper (exit 124) — a known local-env limitation (Testcontainers + this machine), 0 failures/0 errors in the partial run, NOT a test failure**. Per the §6 model, **full-suite no-regression is authoritatively re-proven by CI at PR time** (clean runners, no local file-lock/timeout pathology). Frontier deemed adequately re-validated to proceed; CI at F.9 is the scale gate. Chunk size shrunk to 1 sub-phase/turn going forward (the crash lesson).

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
