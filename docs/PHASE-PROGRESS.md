# GBP OAuth2 access-token refresh — Progress Ledger

> Crash-recovery source of truth for `feat/gbp-oauth-token-refresh`. Each sub-phase is its own
> commit; a DRAFT PR is opened as soon as the code compiles and `git push` runs after every
> sub-phase commit (the crash-hedge). Fresh ledger for this change (supersedes the prior
> GBP-review-reply ledger that occupied this path on `origin/main`).

Branch: `feat/gbp-oauth-token-refresh` (off `origin/main` @ 4ab2a91).
Worktree: `kmo-digipres-be-wt-gbptoken`.

## Problem

The shipped GBP review-reply automation (`integration/gbp/`) authenticates every Google API
call with a stored `accessToken` from `IntegrationConnection(provider="google-business").secrets`.
Google OAuth access tokens expire (~1h). A long-running default-OFF poller therefore hits HTTP
**401** once its stored token ages out, and the review-reply pipeline stalls. This change adds a
**reactive, 401-triggered refresh-and-retry** to the GBP auth path only — the review/reply logic
is untouched.

## Design (mirrors `QuickBooksOAuthService` — the closest in-repo OAuth-refresh precedent)

- **`GbpTokenService`** (new, additive) owns the refresh: a form-encoded `POST` to a **configurable**
  token endpoint (`kmosf.gbp.token-url`, default non-routable `https://oauth2.googleapis.invalid/token`,
  → WireMock in tests) with `grant_type=refresh_token` + the per-tenant `refreshToken` + the OAuth
  app `clientId`/`clientSecret`, parses `access_token`, and **persists** the rotated token to
  `IntegrationConnection(google-business).secrets.accessToken` (+ `tokenExpiresAt`, + a rotated
  `refresh_token` if Google returns one) via `connections.save` under a synthetic
  `TenantContext(tenantId, null, Set.of("INTEGRATION_GBP"))` — the `QuickBooksOAuthService.saveAsTenant`
  pattern. The token-exchange POST shape is the `QuickBooksOAuthService.postTokenForm` mirror
  (`BodyInserters.fromFormData`, `application/x-www-form-urlencoded`).
- **Where `clientId`/`clientSecret` live:** `kmosf.gbp.client-id` / `kmosf.gbp.client-secret` on
  `GbpProperties` — KMOSF-global (one Google Cloud OAuth app serves every tenant), exactly the
  `QuickBooksProperties.clientId/clientSecret` posture. The per-tenant `refreshToken` (+ rotated
  `accessToken`) live in `IntegrationConnection(google-business).secrets`. Documented in
  `GbpProperties` Javadoc + CLAUDE.md.
- **401 trigger in `GbpApiClient`:** when a fetch/post attempt errors with
  `WebClientResponseException.Unauthorized` (401), `GbpTokenService.refreshAccessToken(conn)` runs
  (blocking/crypto-free; the WebClient POST is reactor-native — off the Netty loop by construction),
  then the **original call is retried exactly once** with the new token. A second 401 (or any
  refresh failure) surfaces the new GBP-block error code. The existing Resilience4j retry already
  **excludes** `Unauthorized` (so the breaker/backoff never masks the 401) — the refresh-retry is a
  distinct, single, outer step layered above it.
- **Refresh failure → error code `4034`** (gbp-token-refresh-failed, 502). NOTE — the briefing's
  illustrative "e.g. `4017`" is NOT used: `4017` is already documented in `GlobalErrorHandler` as
  Phase-3 reserved (`4017-4029`, coverage-window growth) and the graded invariant requires the code
  come from the **GBP block** (`4030-4049`). `4034` is the first free code in the GBP-owned
  `4034-4049` reserved range — the correct non-colliding allocation. Recorded deviation.

## Invariants

- §9: the only `switchIfEmpty` in the new/changed code is genuine not-found (the existing
  `resolveConnection`); the refresh path uses explicit error branches, never `switchIfEmpty(create)`.
- §7: token endpoint + GBP base-url both configurable → WireMock in tests; default token-url is a
  non-routable `.invalid`; no live Google; `refreshToken`/`clientSecret` are sandbox fakes; no host
  hardcoded.
- Reused cores empty-diff vs `origin/main`: `IntegrationConnection`(+`Repository`+`Service`), the AI
  services, `GbpReviewPoller`, `GbpReplyDraftService`, `GbpReviewReplyAdminService`. Additive only
  (persisting the rotated secret via the existing `connections.save` is the documented exception).
- `@Bean` (not `@Component`) for any default bean (n/a — `GbpTokenService` is a plain `@Service`,
  a singleton collaborator, not a defaultable seam, so no `@ConditionalOnMissingBean`).

## Sub-phases

| Sub-phase | Status | SHA | Build | Notes |
|---|---|---|---|---|
| SP1 — props + `GbpTokenService` + properties keys | done | (see git log) | compileJava OK | token-url/client-id/client-secret on GbpProperties; refresh POST + persist via synthetic ctx |
| SP2 — `GbpApiClient` 401→refresh+retry-once + `4034` + GlobalErrorHandler Javadoc | done | (see git log) | compileJava OK | both callFetch + callPostReply; review/reply logic untouched |
| SP3 — `GbpTokenRefreshIT` + `GbpTokenServiceTest` + CLAUDE.md note | done | (see git log) | test GREEN | 401-then-200 + persisted-token + refresh-fail→4034 |

## Test result

`./gradlew cleanTest test --tests "*Gbp*" --tests "*OpenApiEndpointIT" verifyOpenApi` — see PR body
for BUILD SUCCESSFUL + per-class counts (incl. the new `GbpTokenRefreshIT`).
