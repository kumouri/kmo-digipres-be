# Phase G — Portal Expansion — Progress Ledger

> Crash-recovery source of truth per ultraplan §6 Resilience. Each sub-phase is its
> own commit; this row is set →in-progress (committed) as the first action and
> →done+results (committed) as the last action of every sub-phase. Plan §10 is a
> one-line pointer here. Plan: back-office-kmo-digipres-phase-G-portal-expansion.md

## Branch: back-office-kmo-digipres-phase-G-portal-expansion  (base main @ cad1f05)
## Model: Plan=Opus 4.7 · Implement=Sonnet 4.6 · Validate/final=Opus 4.7 (access-control + payment focus)

| Sub-phase | Status | SHA | Build (main base / +G / skip) | Mandated checks | Deviations |
|---|---|---|---|---|---|
| G.1 error range 3800-3899 + DomainEventType Phase-G block | in-progress |  |  | no error-code collision | |
| G.2 PortalOwnershipGuard + portal-scoped repo finders | todo |  |  | switchIfEmpty grep; guard reuses resolver; same-404 | |
| G.3 portal projection records + projects/contracts/quotes list services | todo |  |  | switchIfEmpty grep; projections drop tenantId/internal | |
| G.4 portal controllers + invoice pay + INVOICE_VIEWED_BY_CLIENT | todo |  |  | switchIfEmpty grep; gate-before-checkout; reused-services empty diff | |
| G.5 additive-nullable magic-link redirectTo (buildLink unchanged) | todo |  |  | buildLink empty diff; additive-nullable; MagicLinkRedeemIT/PortalChainIT green | |
| G.6 BE ITs AC-G1/G2/G3/G4 (cross-tenant+cross-contact, WireMock pay, deep-link) | todo |  |  | AC covered; cross-contact negative per surface; gate-before-checkout zero-WireMock; shard-safe; no main regression | |
| G.7 CLAUDE.md in-PR + .claude/* local + docs/api/openapi.json committed | todo |  |  | spec has portal paths + Portal*Summary schemas | |
| G.8 final BE green + PR | todo |  |  | Opus validation sign-off | |

## No-live-money / no-live-Documenso boundary (§7) — recorded
- [ ] Portal pay reuses StripeCheckoutService against kmosf.stripe.api-base-url pointed at WireMock in all tests; sandbox sk_test_-shaped fake key; no host hardcoded. Contract Documenso deep-link surfaces the stored documensoDocumentId (+ presign-download of the signed PDF) — no live Documenso call. Live = a separate human action, never the loop.

## Access-control model (the §9 #1 invariant) — recorded
- [ ] Every /portal/me/** data endpoint funnels through PortalLinkedContactResolver (tenant from JWT, contact from User.contactId, tenant-scoped contact load) and, for single-entity ops, PortalOwnershipGuard (tenant-scoped finder + contactId==contact.id || companyId==contact.companyId).
- [ ] Every portal repo finder carries an explicit tenantId AND a contact/company predicate (the TenantScopedReactiveMongoRepository marker does NOT auto-scope derived finders).
- [ ] Not-found and not-owned return the SAME 404 errorCode (no enumeration oracle).
- [ ] PortalSecurityConfig / PortalLinkedContactResolver / QuoteService / QuoteController / StripeCheckoutService / FileStorageService / S3FileStorageService / MagicLinkService.buildLink / DealCrudService / ProjectService — empty diff vs main.

## Validator (Opus 4.7, separate agent) sign-off — access-control + payment focus
BE [ ] — switchIfEmpty grep over service/portal/+controller/portal/ (every hit genuine not-found 1251/1252/2200/38xx/1242/1243/1244): [ ] ·
every portal data method funnels through resolver/ownership-guard; every portal repo finder explicit tenant+contact/company; same-404 not-found vs not-owned: [ ] ·
pay path: ownership gate textually precedes StripeCheckoutService call; @IdempotentRoute on /portal/me/invoices/{id}/pay; WireMock-configurable base URL, sandbox fake key, no host hardcoded: [ ] ·
PortalSecurityConfig/PortalLinkedContactResolver/QuoteService/QuoteController/StripeCheckoutService/FileStorageService/MagicLinkService.buildLink/DealCrudService/ProjectService empty diff vs main: [ ] ·
magic-link redirectTo additive-nullable, persisted-at-request-time not redeem-time, buildLink unchanged, redeem security unchanged (1242/1243/1244): [ ] ·
INVOICE_VIEWED_BY_CLIENT = advisory event + Activity(NOTE) row (best-effort), ActivityType enum unchanged; portal-accept does NOT auto-spawn a contract: [ ] ·
shard-safe: new ITs no @MockBean, no per-IT distinct property source beyond the one WireMock registration, self-clean via mongo.remove @BeforeEach; no application-test.properties/build.gradle shard change: [ ] ·
new-resource-client lifecycle: no new resource-owning bean introduced (explicit "none"): [ ] ·
docs/openapi in-PR; portal controllers no @ConditionalOnProperty: [ ]

## PHASE G COMPLETE — <date>. BE PR #__ merge-commit <sha>. main carries the portal-expansion surface
(Stripe WireMock/sandbox only; Documenso deep-links surface stored ids — no live calls; portal FE deferred per G-D12). Next: Phase H (Activepieces glue / server-side Zitadel portal OIDC).
