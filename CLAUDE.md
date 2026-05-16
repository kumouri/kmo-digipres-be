# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Overview

`kmo-digipres-be` is the backend for the **KMOSF CRM** — a custom multi-tenant CRM / back-office platform being built for KMO Solutions Foundry LLC. The "digipres" name and `com.kumouri` Java package are historical: the project was scaffolded when the owner was still considering splitting KMOSF into two entities. The CRM stayed as a single project after that decision was reversed, but the naming was not refactored. Treat any "digipres" / "kumouri" references as synonyms for the KMOSF CRM — not a separate product.

This is a **near-complete CRM / back-office platform**. The codebase includes multi-tenant scoping, auth, contacts, companies, deals, activities, meetings, booking links, quotes + line items + PDF generation, invoices + payments, product catalog + price lists, S3 file storage, Twilio SMS, Postmark transactional email + webhook ingest, inbox, email sequences, workflow automation (rules + webhook delivery with circuit-breaker), custom field definitions (per-tenant), module registry (per-tenant feature toggles), portal auth (OAuth / Passkey / MagicLink), AI assist, RAG retrieval + AskAI endpoint, embedding pipeline (OpenAI text-embedding-3-small + Atlas Vector Search), lead scoring v2 (RandomForest + rules fallback), GDPR compliance (data-subject requests, consent records, retention policies), reporting + saved reports, mobile delta-sync, service hub (tickets, SLA policies, health scores, knowledge base), home-services vertical (service agreements, equipment, dispatch board, field-service, QuickBooks Online), restaurant-light module, salon/spa module (bookings, loyalty, Square POS), CSV imports, and public widget endpoints. For the full architectural overview and the roadmap, see `docs/design/01-architecture-audit-and-crm-roadmap.md` (untracked — present in the working tree but not committed; see "Architecture design doc" note below).

## Stack

- **Spring Boot 3.5.6** on **Java 21** (toolchain pinned in `build.gradle`)
- **Reactive end-to-end**: `spring-boot-starter-webflux` (Netty) on the web side, `spring-boot-starter-data-mongodb-reactive` with `ReactiveMongoRepository`, and reactive Resilience4j Spring Cloud Circuit Breaker on the classpath. All controllers return `Mono`/`Flux`; all repositories extend `ReactiveMongoRepository` or `TenantScopedSimpleReactiveMongoRepository`; any blocking I/O (e.g. `Transport.send` in `EmailService`, PDF generation in `QuotePdfService`) is wrapped in `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`. Do not introduce blocking patterns — see "Reactive conventions" below.
- **Jakarta Mail** through a hand-rolled `Session` bean (`AngusConfig`) talking to ProtonMail SMTP — *not* `spring-boot-starter-mail`'s `JavaMailSender`
- **Spring's `@Scheduled`** is in active use: `ReportScheduler`, `SlaBreachScheduler`, `ServiceAgreementSchedulerService`, `SequenceEngine`, `EquipmentService`, `LeadScoringV2Service`, and `RetentionPolicyService` all use `@Scheduled` for recurring ticks. **Quartz** is on the classpath but is not yet wired up — these services use Spring's `@Scheduled` as a stop-gap. `ReportScheduler` explicitly notes "swap to Quartz when the job count justifies the overhead."
- **MapStruct 1.6.3** + **Lombok** as annotation processors. Order in `build.gradle` matters; don't reorder the `annotationProcessor` block without verifying MapStruct still generates implementations
- **Spring Cloud Circuit Breaker (Resilience4j, reactor)** is **in active use**: `WebhookDeliveryService` wraps outbound webhook calls with a per-subscription `CircuitBreaker`, and `QuickBooksInvoiceSync` uses a `CircuitBreaker` for QBO API calls. Configuration is in `application.properties` under `resilience4j.circuitbreaker.*` and `resilience4j.retry.*`.
- **Nimbus JOSE + JWT** for HS256 JWT signing/verification (self-issued; no external IdP federation yet — see "Not yet built" below)

`build.gradle` carries a number of **commented-out starters** (Kafka, Spring Integration MongoDB, session-data-mongodb, docker-compose, OTLP registry). These are aspirational scaffolding — don't delete them when cleaning up, and don't assume their features are available.

## Common Commands

All commands run from the repo root and use the Gradle wrapper.

```powershell
# Run the app (expects MongoDB reachable at localhost:27017)
./gradlew bootRun

# Run the app with Testcontainers-managed Mongo + Kafka (no local Mongo needed)
./gradlew bootTestRun  # via TestKmoDigipresBeApplication

# Build (compiles, runs tests, produces jar)
./gradlew build

# Tests only
./gradlew test

# Single test class / method
./gradlew test --tests com.kumouri.kmodigipresbe.KmoDigipresBeApplicationTests
./gradlew test --tests "*KmoDigipresBeApplicationTests.contextLoads"

# Build an OCI image via Paketo buildpacks (uses ubuntu-noble run image)
./gradlew bootBuildImage

# Generate Asciidoctor REST docs (depends on test; reads build/generated-snippets)
./gradlew asciidoctor
```

On Windows, `gradlew.bat` is the equivalent of `./gradlew`.

### MongoDB for local dev

`compose.yaml` maps port `27017:27017` and `application.properties` connects to `mongodb://localhost:27017/kmo-digipres-be`. The compose file and the app agree on the db name, so `docker compose up` works directly for local Mongo.

## Architecture

The codebase follows a layered Spring WebFlux structure under `com.kumouri.kmodigipresbe`. For the full architectural rationale and decision log, read `docs/design/01-architecture-audit-and-crm-roadmap.md` first (untracked file, present in the working tree).

### Architecture design doc

`docs/design/01-architecture-audit-and-crm-roadmap.md` is a 341-line design document that exists untracked in the working tree. It is **not committed** — it predates the `.claude/` gitignore and the owner will decide whether to commit it. Do not delete it; do not commit it without explicit instruction.

### Package overview

- **`controller/`** — REST entry points. Substantial surface area including: `AuthController`, `ContactController`, `CompanyController`, `DealController`, `ActivityController`, `MeetingController`, `AttachmentController`, `BookingLinkController`, `CommunicationController`, `SmsCommunicationController`, `EmailTemplateController`, `ImportController`, `InvoiceController`, `QuoteController`, `ProductController`, `PriceListController`, `ReportController`, `PublicBookingController`, `PublicContactController`, `PublicNewsletterController`, `TenantBootstrapController`, `AuditController` (audit/), `InboxController` (inbox/), `SavedReportController` (report/), `SequenceController` (sequence/), `SyncController` (sync/), `AiAssistController` + `AskAiController` + `LeadScoringController` (ai/), `MarketingLandingPageController` + `PublicLandingPageController` (marketing/), admin subpackage (`FieldDefinitionController`, `ModuleAdminController`, `PortalInvitationController`, `FieldPermissionsController`, `RetentionPolicyController`), compliance subpackage (`ConsentController`, `DataSubjectRequestController`), automation subpackage (`WebhookSubscriptionController`, `WorkflowRuleController`), integration subpackage (`IntegrationConnectionController`, `StripeWebhookController`, `PostmarkWebhookController`, `SquareWebhookController`, `SquareOAuthController`), portal subpackage (`PortalAuthController`, `PortalOAuthController`, `PortalPasskeyController`, `PortalActivitiesController`, `PortalInvoicesController`, `PortalProfileController`, `PortalTicketsController`), servicehub subpackage (`TicketController`, `SlaPolicyController`, `HealthScoreController`, `KnowledgeBaseController`, `PublicKnowledgeBaseController`), forms subpackage (`FormDefinitionController`, `FormWidgetController`), widget subpackage (`SampleWidgetController`), and the `GlobalErrorHandler` advice.
- **`service/`** — Business logic organized per domain: communication (ProtonMail outbound, Postmark transactional, SMS via Twilio), calendar (booking links, booking service), billing (invoices, payments, quotes + PDF via OpenPDF), catalog (products, price lists), AI (`AiAssistService` / `AnthropicAiAssistService`, embedding pipeline, `AskAiService` RAG retrieval, `LeadScoringV2Service`), compliance (`DataSubjectRequestService`, `GdprConsentService`, `RetentionPolicyService`), portal (session, passkey/WebAuthn, MagicLink, OAuth), reports (`ReportScheduler`, `SavedReportCrudService`), service hub (tickets, SLA breach scheduler, health scores, knowledge base), sequences (`SequenceEngine`), sync (`SyncService`), storage (`S3FileStorageService`), templates, imports (`CsvImportService`), and more.
- **`model/`** — Domain entities and DTOs. Key subpackages: `activity`, `ai` (`VectorDocument`, `LeadScoringJob`, `AskAiRequest`), `auth`, `billing`, `calendar`, `catalog`, `communication`, `compliance` (`ConsentRecord`, `DataSubjectRequest`, `RetentionPolicy`), `contact`, `deal`, `files`, `imports`, `inbox`, `meeting`, `report`, `request`, `scoring` (`LeadScore`), `sequence`, `sync`, `template`.
- **`tenancy/`** — Multi-tenancy infrastructure: `TenantScopedSimpleReactiveMongoRepository` (base for all tenant-scoped repos — auto-stamps and auto-filters by `tenantId`), `TenantStampingCallback`, `TenantContextHolder` (Reactor Context carrier), `TenantResolver`, `RoleGuard`, `FieldPermissionPolicy` / `FieldPermissionRedactor`. All repositories for tenant-owned resources extend `TenantScopedSimpleReactiveMongoRepository`, not bare `ReactiveMongoRepository`.
- **`extension/`** — Per-tenant customization: `CustomFieldHost`, `FieldDefinition` + service + repository, `TenantModuleRegistry` (per-tenant feature toggles), `ModuleDefinition`, `EntityType`, `FieldType`.
- **`automation/`** — Event-driven automation: `DomainEvent` + `DomainEventPublisher`, `WorkflowRule` + `RuleEngine`, webhook delivery (`WebhookSubscription`, `WebhookDeliveryService` with Resilience4j circuit breaker).
- **`audit/`** — `Auditable` marker, `AuditEvent`, `AuditingCallback`, `AuditDiffComputer`, `AuditEventWriter`.
- **`integration/`** — Per-tenant external credentials (`IntegrationConnection`), Stripe webhook ingest, Twilio SMS, Postmark, Square (OAuth + webhook + POS).
- **`config/`** — `AngusConfig` (Jakarta Mail session; all SMTP credentials externalized via `@Value`), `JwtConfig` + `JwtProperties` (HS256 signing), `SecurityConfig`, `PortalSecurityConfig`, `FileStorageConfig`, `DataSeeder`.
- **`exceptions/`** — `DigiPresBeException` carries an `errorCode` (numeric, namespaced by subsystem — see `GlobalErrorHandler` Javadoc for the allocation table) and `httpStatusCode`.
- **`module/`** — Optional vertical modules loaded via `ModuleAutoConfigurationSupport`: home-services (`ServiceAgreement`, `Equipment`, `DispatchBoard`, `OnTheWaySmsAutomation`, QuickBooks Online with circuit breaker), field-service (`WorkOrder`, `JobSite`, `Capture`), restaurant-light (reservations, menu, catering orders), salon/spa (bookings, loyalty, Square POS).

### Reactive conventions

- Controllers return `Mono`/`Flux`. `@RequestBody` parameters can be plain DTOs or `Mono<DTO>` — pick the plain form unless streaming or upstream-deferred validation is needed.
- Repositories extend `TenantScopedSimpleReactiveMongoRepository` (for tenant-owned data) or `ReactiveMongoRepository` (for system-level collections).
- **Any blocking I/O** (JDBC, `jakarta.mail.Transport.send`, blocking HTTP clients, OpenPDF) must be wrapped in `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`. `EmailService.sendSingleEmail` and `QuotePdfService` are the reference patterns. Never call blocking code directly on a Netty event-loop thread.
- The base path is `spring.webflux.base-path=/api/v1` (updated in Phase A). The servlet-style `server.servlet.context-path` is silently ignored under WebFlux — do not use it. Legacy `/api/*` paths are transparently rewritten to `/api/v1/*` by `LegacyApiPathRewriteFilter` (removal scheduled start of Phase B once FE targets `/api/v1` directly).

### REST conventions

Server runs on port **8080** with base path **`/api/v1`** (via `spring.webflux.base-path`, updated in Phase A). All errors are translated to **RFC 7807 `ProblemDetail`** responses by `GlobalErrorHandler` (implements `ErrorWebExceptionHandler`, ordered `-2`). The handler covers: `DigiPresBeException` (maps `errorCode` + `httpStatusCode` to a typed problem URI `https://kmosf/errors/<errorCode>`), `WebExchangeBindException` (400 with field-error list), `ResponseStatusException`, `AccessDeniedException` (403), `AuthenticationException` (401), and a catch-all 500. A `correlationId` UUID is included in every error response. Error codes are numeric and subsystem-namespaced — see the Javadoc on `GlobalErrorHandler` for the full allocation table. **Phase A error ranges: `3100–3199` idempotency; `3200–3299` versioning/auth-mode.**

### SMTP / secrets

All SMTP credentials are externalized to environment variables via `@Value` in `AngusConfig` (`KMOSF_MAIL_SMTP_HOST`, `KMOSF_MAIL_SMTP_PORT`, `KMOSF_MAIL_SMTP_USERNAME`, `KMOSF_MAIL_SMTP_PASSWORD`). Nothing is hardcoded. The password has no default — the app will fail to start if the env var is absent. There is no secret store integration yet (env vars only).

### JWT / auth

JWTs are **self-issued HS256** by default (auth mode `local`), signed with a secret from `KMOSF_JWT_SECRET` (env var). If unset, a random ephemeral key is generated and a WARN is logged — tokens invalidate on every restart; not suitable for production. Key must be >= 32 bytes.

**Phase A auth-mode switch:** `kmosf.auth.mode` controls which `ReactiveJwtDecoder` is active:
- `local` (default, `KMOSF_AUTH_MODE` unset) — existing HS256 decoder; no Zitadel config needed.
- `zitadel` — JWKS-backed decoder via `NimbusReactiveJwtDecoder.withJwkSetUri(...)`. Requires `KMOSF_AUTH_ZITADEL_JWKS_URI`; blank URI fails fast at startup. In Phase A, `JwtTenantResolver` still reads `tid`/`uid`/`roles` claims — a Zitadel token without those fails with errorCode 1002 (expected; Phase A2 adds full Zitadel claim mapping).

**Discovery endpoint:** `GET /api/v1/auth/discovery` (unauthenticated) returns `{mode, issuerUri, jwksUri, loginPath}`.

**Idempotency:** `@IdempotentRoute` (method-level annotation) opts a POST/PUT/PATCH endpoint into idempotency enforcement via `IdempotencyWebFilter`. `Idempotency-Key` header required on annotated routes (missing → 400/3100); duplicate calls within 24h replay the cached response. System collection `idempotency_keys`. `CommunicationController.sendEmail` is the sample annotated endpoint; Invoice/Payment adoption is Phase E.

**Quartz:** The scheduler is wired and a no-op proof job fires ~5s after boot (behind `kmosf.quartz.proof-job.enabled`, default true). Uses Spring Boot RAM store in Phase A (Quartz Mongo JobStore library coordinates unresolved; see R-A4 note in `build.gradle`). Existing `@Scheduled` services are unchanged. Full Quartz Mongo store + `@Scheduled`→Quartz migration is Phase E.

**OpenAPI:** Spec served at `GET /api/v1/v3/api-docs` (JSON) and `/api/v1/openapi` (Swagger UI), unauthenticated. Committed to `docs/api/openapi.json`. `./gradlew verifyOpenApi` (`check`-gated) fails the build on drift. Regenerate after any controller/model changes: `./gradlew generateOpenApiDocs`, copy `build/openapi/openapi.json` to `docs/api/openapi.json`, commit.

## Testing

Tests use Spring Boot Test with **Testcontainers** for Mongo and Kafka (`TestcontainersConfiguration`). Kafka is wired into the test container set even though the main app's Kafka dependencies are commented out — this is intentional scaffolding; do not remove the Kafka testcontainer when Kafka isn't in scope unless you also remove the commented Kafka deps.

`TestKmoDigipresBeApplication` is a `main`-method entry point for running the real app against Testcontainers-managed infrastructure — use it (or `./gradlew bootTestRun`) for local manual testing when you don't want a real Mongo running.

REST Docs is configured (`spring-restdocs-webtestclient` + asciidoctor plugin). When endpoints are documented, snippets land in `build/generated-snippets` and the `asciidoctor` task assembles them.

## Not yet built / next

The following are **not in the codebase** as of the current commit — do not assume they exist:

- **Project / Milestone / Task / TimeEntry / Expense** entities (project management vertical)
- **Contract + ContractTemplate / RecurringInvoice** entities
- **Zitadel / external IdP federation Phase A2** — `kmosf.auth.mode=zitadel` wires the JWKS decoder but `JwtTenantResolver` still reads `tid`/`uid`/`roles` — full Zitadel claim mapping, JIT provisioning, role sync is Phase A2
- **Quartz Mongo JobStore** — wired proof job uses RAM store (Phase A); Mongo store + `@Scheduled`→Quartz migration is Phase E
- **`@IdempotentRoute` adoption on Invoice/Payment** — infrastructure is built; actual adoption on money-critical endpoints is Phase E
- **FE codegen switch** — BE now publishes OpenAPI at `/api/v1`; FE `openapi-typescript` codegen switch is Phase B
- **Per-tenant deployment automation**

These are planned in the back-office ultraplan (`C:\Users\willa\.claude\plans\ultraplan-research-back-office-iridescent-wilkes.md`). Do not scaffold or stub these unless a specific phase in that plan is actively in flight.

## Branching

This repo lives under `kmosf/repos/` in the KMOSF workspace and follows the workspace branching conventions (see `../../CLAUDE.md`): `<plan-slug>` for single-phase plans, `<plan-slug>-phase-N-<desc>` only when one plan is split, `fix/<desc>` and `feat/<desc>` for unplanned work. The default branch is `main` (renamed from `master`); don't work directly on it.
