# PHASE-PROGRESS — fix/security-p0-authz (Security PR A, BE-01..05)

> Fresh ledger for this branch (off `origin/main`). Replaces the prior
> `gate2-nurture-copyfilter-scoping` ledger that occupied this path — that work is already
> on `main`. Tracks per-sub-phase progress + validation so a resume-after-crash
> reconstructs the frontier from git + this file, never agent memory.

Driver: `.claude/security-audit-2026-06-09.md` + `.claude/security-remediation-plan-2026-06-09.md`
(PR A — Backend P0). Branch: `fix/security-p0-authz` (local only; orchestrator pushes/merges).

## Sub-phases

| # | Finding | Status | Validation |
|---|---------|--------|------------|
| 1 | BE-04 + BE-02 — central default-deny `StaffAuthorizationWebFilter` + SecurityConfig/DSR Javadoc | DONE | `StaffAuthorizationFilterIT` |
| 2 | BE-01 — portal-invitation role whitelist (controller 400 + UserIdentityService defensive drop) | DONE | `PortalInvitationRoleIT` |
| 3 | BE-03 — integration-secret redacted read DTO + write DTO + ADMIN gate (from filter) | DONE | `IntegrationConnectionRedactionIT` |
| 4 | BE-05 — magic-link `linkBaseUrl` removed; server-derived base | DONE | `MagicLinkLinkBaseUrlIT` |

## Error codes allocated (audit/compliance band 1800-1899, alongside RoleGuard's 1800)
- `1802` — portal CLIENT token rejected on the staff chain (403, BE-04)
- `1803` — STAFF baseline role required on the staff chain (403, BE-02)
- `1804` — ADMIN role required for `/admin/**` or `/integrations/connections/**` (403, BE-02)
- `1820` — portal invitation may only grant CLIENT (400, BE-01)

## Filter design (authoritative)
- Bean `tenancy/StaffAuthorizationWebFilter`, `@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 2)`
  (after `TenantWebFilter` at +1 so `TenantContext`/roles are populated; reads the `portal`
  claim straight off the JWT principal since `TenantContext` does not carry it).
- Excluded (permitAll/public surface, mirrors `SecurityConfig`): `/portal/**`, `/public/**`,
  `/auth/login`, `/auth/health`, `/auth/discovery`, `/tenants`, `/openapi`, `/v3/api-docs/**`,
  `/swagger-ui/**`, `/webjars/**`, `/actuator/health/**`.
- (a) `portal` claim == CLIENT → 403/1802; (b) baseline STAFF → else 403/1803;
  (c) ADMIN for `/admin/**` + `/integrations/connections/**` → else 403/1804.
- CONTRACTOR/ADMIN users always also carry STAFF (`TeamService.sanitizeRoles`: "CONTRACTOR /
  ADMIN ride the staff security chain — keep STAFF present"), so the baseline never blocks a
  legitimate staff-chain principal. Existing `RoleGuard` calls kept as defense-in-depth.

## Files changed
- NEW `tenancy/StaffAuthorizationWebFilter.java`
- `config/SecurityConfig.java` — Javadoc references the filter
- `controller/compliance/DataSubjectRequestController.java` — corrected false "enforced in SecurityConfig" Javadoc
- `controller/admin/PortalInvitationController.java` — reject non-CLIENT roles (400/1820); force CLIENT
- `service/portal/UserIdentityService.java` — `addRoles` drops any non-CLIENT invitation role
- `controller/integration/IntegrationConnectionController.java` — redacted `ConnectionView` reads + `ConnectionWrite` writes
- `integration/IntegrationConnection.java` — Javadoc (redaction + encrypt-at-rest TODO)
- `controller/portal/PortalAuthController.java` + `service/portal/MagicLinkService.java` — drop `linkBaseUrl`
- NEW tests: `StaffAuthorizationFilterIT`, `PortalInvitationRoleIT`, `IntegrationConnectionRedactionIT`, `MagicLinkLinkBaseUrlIT`
- Javadoc-only test touch: `PortalMagicLinkDeepLinkIT` (stale `buildLink(linkBaseUrl,...)` comment)

## Deferred / notes
- BE-03 encrypt-secrets-at-rest: `// TODO(security BE-03): encrypt secrets at rest` left in
  `IntegrationConnection` Javadoc. Redaction + ADMIN gate are shipped (the must-haves).
- OpenAPI: `IntegrationConnectionController` response shape changed → `docs/api/openapi.json`
  is auto-refreshed by `OpenApiEndpointIT` during the test phase (`verifyOpenApi` is advisory,
  never fails the build).
