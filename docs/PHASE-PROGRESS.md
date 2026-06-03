# NMM GBP review-reply automation — Progress Ledger

> Crash-recovery source of truth. Each sub-phase is its own commit; this row is set
> →done+results (committed) as the last action of every sub-phase. A DRAFT PR is opened
> after 4G.1 compiles and `git push` runs after EVERY sub-phase commit (the crash-hedge).
> Plan/context: `i-had-an-idea-ticklish-rivest.md` — a candidate-workflow pivot (replaces the
> original thin "neighbor lead-gen" Phase 4; "GBP review-reply automation" was on NMM's
> candidate-workflow list), NOT a numbered §5 Phase. Builds on the merged Phase-1 (voicemail)
> + Phase-2 (photo-triage) + Phase-3 (coverage-window) NMM AI-intake spine.

## Branch: nmm-gbp-review-reply-automation  (base main @ 6f3e9cb — the merge that includes Phases 1+2+3)
## Model: Opus implementer = Opus 4.8 (1M context). BE-only — no FE. No live external services (§7 — WireMock/sandbox/.invalid throughout; the poller default-OFF; live Google OAuth/GBP is a deferred, Google-approval-gated human step).

| Sub-phase | Status | SHA | Build (compileJava+compileTestJava) | Mandated checks | Deviations |
|---|---|---|---|---|---|
| 4G.1 error range 4030-4049 (claims Phase-2's reserved 4030-4039, extended to 4049) + DomainEventType GBP block + GbpReviewReply ledger/draft entity + repo + GbpProperties/GbpConfig + config props | done | (this commit) | compileJava BUILD SUCCESSFUL exit 0 | GlobalErrorHandler Javadoc: 4030 GBP not connected (404, Phase-local; 2510 cross-integration fallback), 4031 GBP API fetch/post failed (502), 4032 review-reply draft not found (404), 4033 review-reply not DRAFTED — cannot post/skip (409, defensive); reserved 4034-4049; reused not re-allocated 1200-1203 (AI — GbpReplyDraftService), 2530-2532 (Twilio SMS — reused TwilioSmsService notify), 1300 (Activity), 1800 (RoleGuard ADMIN). DomainEventType +2 advisory constants (GBP_REVIEW_REPLY_DRAFTED / GBP_REVIEW_REPLY_POSTED). GbpReviewReply (@Document gbp_review_replies, TenantScoped NOT Auditable — the CalComWebhookEvent/RecurringInvoiceOccurrence rationale) = idempotency ledger (unique tenant_review_idx {tenantId,reviewId}, ledger-insert-FIRST) AND the persisted draft Rob approves: {reviewId, rating, comment, reviewerName, reviewCreateTime, draftedReply, status DRAFTED→POSTED|SKIPPED, postedAt}. GbpReviewReplyRepository: findByTenantIdAndReviewId (the explicit-boolean probe — NEVER switchIfEmpty(process)), findByTenantIdAndStatusOrderByReceivedAtDesc + findByTenantIdAndId (explicit tenantId predicates). GbpProperties (kmosf.gbp.api-base-url default https://gbp.googleapis.invalid — non-routable .invalid; requestTimeoutSeconds) registered via GbpConfig @EnableConfigurationProperties (the DocumensoConfig precedent). Config props: kmosf.modules.gbp-reviews.{enabled=false (DEFAULT-OFF poller), auto-post=false, poll-interval-ms, initial-delay-ms}, kmosf.gbp.{api-base-url, request-timeout-seconds, draft-model=sonnet, reply-system-prompt}, resilience4j gbp-reviews breaker (the stripe-checkout pattern). | none |
| 4G.2 GbpApiClient (raw WebClient, configurable base-url → WireMock, Resilience4j gbp-reviews breaker: fetchReviews + postReply) + GbpReplyDraftService (mirrors VoicemailExtractionService/AnthropicAiAssistService — NEW additive sibling, the core stays empty-diff) | pending | | | | |
| 4G.3 GbpReviewPoller (@Scheduled + @ConditionalOnProperty gbp-reviews matchIfMissing=false DEFAULT-OFF; per connected tenant fetch → explicit-boolean review-id idempotency + ledger-insert-FIRST → draft best-effort → persist DRAFTED → notify Rob → GBP_REVIEW_REPLY_DRAFTED; optional auto-post → postReply + POSTED + GBP_REVIEW_REPLY_POSTED) | pending | | | | |
| 4G.4 GbpReviewReplyAdminController (ADMIN-guarded, @ConditionalOnProperty gbp-reviews matchIfMissing=true: GET list DRAFTED + POST {id}/post + POST {id}/skip) | pending | | | | |
| 4G.5 BE ITs (GbpReviewPollerIT poller-opted-ON; GbpReviewReplyAdminIT; GbpReplyDraftServiceIT) GREEN | pending | | | | |
| 4G.6 docs (CLAUDE.md GBP section + openapi.json regen) + final ITs GREEN + mark PR ready | pending | | | | |

## No-live-external boundary (§7) — recorded
- [ ] GBP + Anthropic base URLs configurable and pointed at WireMock in every test; poller default-OFF; notify seams @MockitoBean; OAuth creds/keys sandbox fakes; NO host hardcoded; NO live OAuth/post/charge/send anywhere. (Filled in at 4G.5.)

## §9 reactive invariant — recorded
- [ ] switchIfEmpty over the new integration/gbp package = genuine not-found only; ZERO switchIfEmpty(create/process/draft/post); the review-id idempotency seam is explicit-boolean + ledger-insert-FIRST; blocking work off the Netty loop. (Filled in at 4G.5/4G.6.)
- [ ] Reused cores empty-diff vs the base 6f3e9cb (NOT local `main`). (Filled in at 4G.6.)

## Baseline note (IMPORTANT for the validator)
The branch is based on `6f3e9cb` (the merge that includes Phases 1+2+3). Reused-cores empty-diff
MUST be verified against `6f3e9cb` (the correct base), NOT local `main` (which may be behind that
merge — a `git diff main` would falsely show Phase-1/2/3 files as new, the Phase-2/3 lesson).

## Opus implementer note
Implemented by Opus 4.8 (1M context) as a single supervised pass; Docker is up so the ITs are run
to GREEN locally before the PR is marked ready (the non-negotiable gate). Crash-hedge: push + open
a DRAFT PR as soon as 4G.1 compiles, `git push` after EVERY sub-phase commit, mark the PR ready
only at the end. Each sub-phase: explicit `git add <paths>` (never -A/.; never the root CLAUDE.md
or `.claude/*`), compileJava+compileTestJava after each, full ITs to GREEN at the end.
