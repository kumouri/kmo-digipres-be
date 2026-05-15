# Architecture Audit & CRM Roadmap — First Pass

**Project:** `kmo-digipres-be` (KMOSF CRM backend)
**Audited at commit state:** 2026-05-12
**Scope:** Backend Spring WebFlux service; no frontend in this repo.
**Strategic assumptions confirmed with owner:** multi-tenant from day one, API-first / embeddable widgets are the primary consumer surface, KMOSF-internal admin UI is in scope soon, competitive baseline is Odoo CRM + HubSpot Free.

---

## 0. TL;DR

The codebase is at **"scaffold + one end-to-end vertical slice"** stage — `POST /api/communication/singleEmail` works, everything else is a stub. The reactive plumbing (WebFlux + Reactive Mongo + Reactor) is clean and the layering (controller → service → mapper → model) is well thought out for what's there.

But the **strategic gap is much bigger than the code gap.** Three architectural decisions have to land before any CRM feature work, because retrofitting them later will be punishing:

1. **Tenancy.** There is no concept of a tenant anywhere — no `tenantId` field, no tenant-resolution filter, no per-tenant config namespace. Multi-tenant has to be a schema invariant, not a feature.
2. **Identity & authorization.** Spring Security is on the commented-out list. There's currently nothing stopping anonymous internet traffic from calling `singleEmail` and using your ProtonMail account to spam. This blocks any production deployment, not just CRM features.
3. **Error contract.** `DigiPresBeException` carries an HTTP status but nothing translates it into a response — every business error bubbles as a 500 with a stack trace. Public APIs and embeddable widgets need a stable error envelope before client code starts depending on the API shape.

Fix those three and the CRM feature roadmap becomes a pleasant build-out. Skip them and every Phase-2 feature will compound the debt.

---

## 1. CLAUDE.md Drift Report

`CLAUDE.md` is broadly accurate but has drifted in a few spots. Worth fixing the doc so future sessions start from truth:

| CLAUDE.md claim | Actual state | Action |
|---|---|---|
| "ProtonMail SMTP password is currently hardcoded in source (`AngusConfig.java:24`)" | **No longer true.** All four SMTP values are read from env vars via `@Value` in `AngusConfig.java:15–25`, and `application.properties:7–10` resolves them from `KMOSF_MAIL_SMTP_*` environment variables. Password has no default — app won't start without it. | Remove the warning from `CLAUDE.md`; replace with a note that secrets are externalized but there's no secret store integration yet (env vars only). |
| "Quartz [is] declared, not yet used" | Correct — `spring-boot-starter-quartz` is on the classpath but no `JobDetail`/`Trigger` beans exist. | No change. |
| "Spring Cloud Circuit Breaker [is] declared but not yet applied anywhere" | Correct. | No change. |
| `EmailService.sendSingleEmail` is "the reference pattern" for blocking-I/O wrapping | Correct — `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` at `EmailService.java:24,45`. | Keep this; it's the right pattern. |
| "No `@ControllerAdvice` translating [`DigiPresBeException`] to an HTTP response — exceptions will bubble out as 500s" | Confirmed. There's no exception handler anywhere in the tree. | Listed below as a Critical fix. |
| `compose.yaml` port-mapping and db-name-case mismatches | Both confirmed (`compose.yaml:9` exposes `'27017'` with no host binding; `compose.yaml:5` sets `KMO_DIGIPRES_BE` but `application.properties:3` connects to `kmo-digipres-be`). | Fix when touching local-dev workflow. |

There's also a **stale git worktree at `.claude/worktrees/wizardly-dijkstra-b74bb3/`** with a duplicate snapshot of the source tree from an earlier agent run. It's confusing search results and bloating the repo. Run `git worktree prune` and `rm -rf .claude/worktrees/` (or wire it into `.gitignore` if you intend to keep `.claude/` working state).

---

## 2. Architecture Audit

Findings are grouped by severity. Each finding has a stable ID so we can reference them in commits and tickets.

### 2.1 Critical (block production / block CRM feature work)

**C1 — No multi-tenancy primitive in the domain model.**
- *Evidence:* `Meeting.java` and `EmailContact.java` have no `tenantId` field, no `@Indexed` on a tenant column. Repositories don't scope by tenant. There is no `TenantContext` / `ReactiveSecurityContext` holder.
- *Why it's critical:* You confirmed the product is multi-tenant from day one. The Mongo collection design has to bake tenant scoping in *before* data starts landing, or every migration after that becomes "rewrite every document."
- *Fix shape:* See §5 — every `@Document` gets a `String tenantId`, every reactive repo method goes through a `TenantScopedRepository` aspect or query helper, and inbound requests resolve tenant from the auth principal (or, for public widgets, from a signed embed token).

**C2 — No authentication or authorization layer.**
- *Evidence:* `spring-boot-starter-security` is commented out in `build.gradle:39`. `CommunicationController.java` accepts unauthenticated POSTs. The single live endpoint can be used by anyone on the network to send mail through your ProtonMail account.
- *Why it's critical:* Even pre-CRM, this is a deployment-blocker. The cost of having KMOSF's mail credentials abused for spam (rate limits, deliverability tarpitting, account suspension) is high.
- *Fix shape:* Spring Security WebFlux + a chosen auth model. For an API-first multi-tenant product, the canonical pair is OAuth2 Resource Server (JWTs) for first-party admin UI + API keys (hashed, scoped, rotatable) for server-to-server and embed-widget callers. Decision needed — see Open Questions §7.

**C3 — `DigiPresBeException` has no translator; everything bubbles as 500.**
- *Evidence:* No `@ControllerAdvice` / `WebExceptionHandler` anywhere. `DigiPresBeException.java` carries `errorCode` and `httpStatusCode` fields that are never read by anything.
- *Why it's critical:* Embeddable widgets and third-party API consumers will be writing client code against your error responses. Right now those responses are HTML stack traces. The contract has to stabilize *before* clients ship.
- *Fix shape:* Add a `GlobalExceptionHandler` annotated `@RestControllerAdvice` with `@ExceptionHandler` methods for `DigiPresBeException`, `WebExchangeBindException` (validation), `ResponseStatusException`, and generic `Throwable`. Define a `Problem` / `ErrorEnvelope` record (RFC 7807 Problem-Details is the well-trodden path). Wire it into `CommunicationController` immediately so the first vertical slice has the right shape.

### 2.2 High (architectural choices that constrain future work)

**H1 — `Contact` is annotated `@Document` on an interface.**
- *Evidence:* `Contact.java:5–7` — `@Document public interface Contact`. The `@Document` annotation on an interface is at best a no-op (Spring Data Mongo uses it to drive collection mapping for concrete persistent types) and at worst will surprise the next developer.
- *Why it matters:* The concrete `EmailContact` is a `record` (no `@Document` itself) wrapping a `jakarta.mail.internet.InternetAddress`. Mongo will need a custom `Converter` for `InternetAddress` (Spring Data Mongo doesn't know how to read/write it natively), and polymorphic storage of `Contact` subtypes (e.g. `Meeting.organizer` and `Meeting.attendees`) needs a `_class` discriminator strategy.
- *Fix shape:* Decide whether `Contact` is (a) a persistent root collection in its own right, or (b) a sealed-type value object embedded in other documents. (a) → move `@Document` onto each concrete subtype, add an `@Id String id` to every implementation, define a `ContactRepository<T extends Contact>`. (b) → drop `@Document` from the interface, add a `@TypeAlias` to each concrete type so Mongo's `_class` discriminator is human-readable. Either way, register a `Converter<InternetAddress, String>` and its inverse in a `MongoCustomConversions` bean.

**H2 — `Meeting` has no `@Id` field, no audit fields, no tenancy.**
- *Evidence:* `Meeting.java` declares `name, description, location, start, end, allDay, organizer, attendees` and nothing else. `MeetingRepository extends ReactiveMongoRepository<Meeting, UUID>` so the key type is `UUID` but the document has no `UUID id` field for Spring Data to bind it to. This will work (Mongo auto-assigns ObjectId, Spring will silently treat it as the key) but the `UUID` type parameter is a lie.
- *Why it matters:* Every domain document in a CRM needs `id`, `tenantId`, `createdAt`, `updatedAt`, `createdBy`, `updatedBy`. Without them, you can't build "last 10 contacts modified" views, audit trails, optimistic locking, or webhooks. Better to set the abstract base class now.
- *Fix shape:* Introduce an `abstract class TenantScopedDocument` (or a Lombok `@SuperBuilder` mixin) carrying `@Id UUID id`, `String tenantId`, `Instant createdAt`, `Instant updatedAt`, `String createdBy`, `String updatedBy`, `@Version Long version`. Make every `@Document` extend it. Enable `@EnableReactiveMongoAuditing` on the `@SpringBootApplication` class.

**H3 — `SchedulingController` is a Spring bean with no HTTP mapping.**
- *Evidence:* `SchedulingController.java:7` — `@RestController(value = "scheduling")`. That `value` is the **bean name**, not the request path. There's no `@RequestMapping` and no methods, so this is an empty bean with a misleading-looking annotation.
- *Why it matters:* Anyone reading this will assume scheduling endpoints live under `/scheduling`. They don't. This is the kind of subtle drift that bites a year from now. Either flesh it out or delete it.
- *Fix shape:* Either `@RestController @RequestMapping("/scheduling")` with stub methods that return `ResponseEntity.notFound()`, or delete the file. (Per your "scaffolded but unimplemented" stance, I'd keep it but mark the request mapping explicitly.)

**H4 — `CommunicationController` returns `Mono<Boolean>` with no response envelope.**
- *Evidence:* `CommunicationController.java:22` returns `Mono<Boolean>` directly. No status code shaping, no idempotency key, no correlation ID, no retry semantics.
- *Why it matters:* Email sending is a side-effect-with-no-undo. If the client retries on a network hiccup, you'll double-send. The response contract also won't survive contact with reality — the next caller will need a message ID, a delivery status, a timestamp. Bake the envelope shape now so all subsequent endpoints copy it.
- *Fix shape:* Define a `SendCommunicationResult` record (`messageId`, `acceptedAt`, `provider`, `status`). Accept an `Idempotency-Key` header; cache the result in Mongo keyed by `(tenantId, idempotencyKey)` with a TTL.

**H5 — No request validation, despite `spring-boot-starter-validation` on the classpath.**
- *Evidence:* `SingleEmailCommunicationDTO.java` has no constraint annotations. `CommunicationController.java:22` has no `@Valid`. Validation starter is paid for in `build.gradle:40` but not used.
- *Why it matters:* The first malformed email coming in will surface as a `MessagingException` deep inside the reactive chain instead of a clean 400 Bad Request. And `EmailUtil.fromString` silently throws a `DigiPresBeException(0, 400)` — fine, but mass-rejecting bad inputs at the controller boundary is cheaper than letting them flow.
- *Fix shape:* Add `@NotBlank @Email` on `to`/`from`, `@NotBlank @Size(max = 200)` on `subject`, `@NotBlank @Size(max = 1_000_000)` on `body`. Add `@Valid` to the `@RequestBody` parameter. The exception handler from C3 will format `WebExchangeBindException` into your error envelope.

**H6 — No API surface contract (OpenAPI / Swagger).**
- *Evidence:* No `springdoc-openapi` dependency, no `/v3/api-docs` endpoint, no schema export.
- *Why it matters:* You're API-first by strategy. Without OpenAPI you can't generate client SDKs for embed widgets, you can't drive Postman/Bruno collections, you can't auto-generate the admin UI's typed client. This costs you for the rest of the project's life.
- *Fix shape:* Add `org.springdoc:springdoc-openapi-starter-webflux-ui` (the WebFlux-compatible flavor) and `springdoc-openapi-starter-webflux-api`. Set `springdoc.api-docs.path=/openapi`. Treat the generated spec as the API contract — commit it to the repo (`docs/api/openapi.json`) and diff-check on PRs.

### 2.3 Medium (cleanup that pays for itself within a phase)

**M1 — `EmailUtil` is annotated `@Component` but contains only a static method.**
- *Evidence:* `EmailUtil.java:8–17` — `@Component public class EmailUtil { public static InternetAddress fromString(String email) {...} }`.
- *Fix:* Drop `@Component`. Either keep the static utility shape or convert to a `final` class with a private constructor (idiomatic).

**M2 — `EmailService.initiateContact` uses `instanceof` pattern matching but returns `Mono.just(false)` for unrecognized requests.**
- *Evidence:* `EmailService.java:48–54`.
- *Why it matters:* This swallows misuse. A future `BulkEmailCommunicationRequest` will silently return "false" with no signal, instead of failing loud.
- *Fix:* Throw a `DigiPresBeException` for unhandled subtypes, or split `ContactService` into per-subtype methods. Once you have multiple subtypes, a sealed-interface pattern with exhaustive switch is the cleanest.

**M3 — No observability beyond the actuator default.**
- *Evidence:* `micrometer-tracing-bridge-brave` is on the classpath (`build.gradle:42`) but there's no exporter (Zipkin / OTLP) — the OTLP registry is commented out at line 56. No structured logging config, no MDC propagation for `tenantId` / `correlationId` / `requestId`.
- *Fix:* Decide on an exporter target (likely OTLP → Grafana/Tempo or self-hosted Jaeger). Add MDC enrichment in a `WebFilter` that puts `tenantId`, `correlationId`, `principalId` into the reactor context and into Logback's MDC via Reactor's context-propagation library.

**M4 — `RequestMapper` is incomplete; will not scale to the planned channel surface.**
- *Evidence:* `RequestMapper.java` only knows about `SingleEmailCommunicationDTO` ↔ `SingleEmailCommunicationRequest`. As soon as you add SMS, calendar invites, in-app notifications, you'll either inflate one mapper to 30+ methods or duplicate the pattern badly.
- *Fix:* Split into channel-scoped mappers (`EmailMapper`, `SmsMapper`, `MeetingMapper`) with a shared `ContactMapper` for the common string→`Contact` conversions. MapStruct supports `@Mapper(uses = {ContactMapper.class})` for delegation.

**M5 — Mongo `auto-index-creation=true` in `application.properties:4`.**
- *Evidence:* `spring.data.mongodb.auto-index-creation=true`. Convenient for dev, dangerous for prod (index builds on hot collections can stall traffic).
- *Fix:* Override to `false` in `application-prod.properties` (which doesn't exist yet) and either ship index definitions via a migration tool (Mongock / Liquibase-Mongo) or check them in as `IndexOperations` calls run on startup behind a feature flag.

**M6 — `spring-boot-devtools` in `implementation`-time scope is harmless but `developmentOnly`-scoped here is correct (build.gradle:54).** Just noting that this is fine — don't change it. Anyone reading the file may flag it; you can point them here.

### 2.4 Low (housekeeping)

**L1 — `compose.yaml:9` exposes port `'27017'` (random host port) but `application.properties:2` hardcodes `localhost:27017`.** Map the port explicitly to `'27017:27017'` or use Spring Boot's Docker Compose support (the `spring-boot-docker-compose` dep is commented out at `build.gradle:55` — un-commenting it would let Spring auto-wire the container URI).

**L2 — `compose.yaml:5` initializes db `KMO_DIGIPRES_BE` but `application.properties:3` connects to `kmo-digipres-be`.** Pick one (lowercase-hyphenated matches the Spring app name convention; align compose to that).

**L3 — `KmoDigipresBeApplication.java` is a default Spring Boot stub.** It will need `@EnableReactiveMongoAuditing`, `@EnableConfigurationProperties(KmosfMailProperties.class)` (once you type-safe the mail config), and possibly `@EnableScheduling` once Quartz starts being used. Plan for it.

**L4 — `.claude/worktrees/wizardly-dijkstra-b74bb3/` is a stale snapshot polluting search results and git status.** Prune it.

---

## 3. Strategic Principles for the Rebuild

Before features, lock these principles. They show up as invariants in every subsequent design decision.

1. **Tenancy is a schema-level invariant, not a runtime check.** Every persistent document carries `tenantId`. Every query is tenant-scoped at the repository layer, not the controller layer. A query that forgets `tenantId` should fail to compile (or fail loudly in test) — not "leak data sometimes."
2. **The API is the product.** Every controller endpoint is a public contract. OpenAPI is the source of truth. Versioning lives in the URL (`/api/v1/...`). Breaking changes ship behind a new version, not a quiet schema edit.
3. **Idempotency is mandatory for state-changing endpoints.** `Idempotency-Key` header is required on POST/PUT/PATCH. Stored with a TTL keyed by `(tenantId, key)` and used to short-circuit replays.
4. **Errors are RFC 7807 Problem-Details, no exceptions.** No HTML, no stack traces, no inconsistent shapes. Every error has a `type`, `title`, `status`, `detail`, `instance`, and `traceId`.
5. **No business logic in controllers, no MongoDB in services.** Controller → Service → Repository. The service layer is the only place where business invariants live. Repositories are reactive and pure (no joins, no orchestration).
6. **Blocking-I/O wrapping is non-negotiable.** Any blocking call (SMTP, JDBC, third-party SDK that isn't reactive) is wrapped in `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`. The `EmailService` pattern is the reference.
7. **Embed widgets are first-class citizens, not afterthoughts.** Each widget gets a scoped, signed embed token (short TTL, rotatable, revocable). CORS allow-list is per-tenant. Rate limits are per-widget-token + per-IP.

---

## 4. CRM Feature Roadmap — Benchmarked Against Odoo CRM & HubSpot Free

This is the *what*, not the *when*. Phasing in §5 turns it into *when*.

### 4.1 Domain Model (the canonical CRM nouns)

| Entity | Odoo (`res.partner`, `crm.lead`, `crm.team`, etc.) | HubSpot Free | KMOSF baseline |
|---|---|---|---|
| **Contact** (a person) | ✅ shared with vendors/customers | ✅ Free | ✅ Must-have |
| **Company / Account** (the org a contact belongs to) | ✅ same record, "is_company" flag | ✅ Free | ✅ Must-have (separate doc, link by `accountId`) |
| **Lead** (unqualified inbound) | ✅ `crm.lead` (lead/opportunity unified) | ✅ Free (limited) | ✅ Must-have — this is the widget intake surface |
| **Deal / Opportunity** (qualified, in pipeline) | ✅ same `crm.lead` with stage | ✅ Free | ✅ Must-have |
| **Pipeline / Stage** | ✅ configurable per team | ✅ Free (single pipeline on Free tier) | ✅ Must-have (multi-pipeline from start; pipelines are per-tenant) |
| **Activity** (call, meeting, task, note) | ✅ via `mail.activity` | ✅ Free | ✅ Must-have |
| **Task** | ✅ separate `project.task` | ✅ Free | ✅ Phase 2 |
| **Note** | ✅ `mail.message` | ✅ Free | ✅ Must-have |
| **Email log** (sent + received, threaded) | ✅ `mail.thread` | ✅ Free (with quota) | ✅ Phase 2 — current single-email send is the seed |
| **Meeting** (calendar) | ✅ `calendar.event` | ✅ Free (Meetings tool) | 🟡 Scaffolded already; needs invite delivery + ICS |
| **Form / Lead-capture widget** | ✅ Website Builder add-on | ✅ Free (HubSpot Forms) | ✅ **KMOSF differentiator** — must be best-in-class for embeddable use case |
| **Campaign / Sequence** | ✅ Marketing module (paid) | 🟡 Workflows (Starter+) | ⚠️ Phase 3 — explicitly out of MVP |
| **Quote / Proposal** | ✅ Sales module | ❌ (CRM-side) | ⚠️ Phase 3 |
| **Ticket / Helpdesk** | ✅ Helpdesk module | ✅ Service Hub Free | ⚠️ Phase 3 (KMOSF could differentiate here for client support handoff) |
| **Custom fields** | ✅ `studio` (paid) / dev mode | ✅ Free (limited count) | ✅ Phase 2 must-have — every client tenant will want their own |
| **Webhooks** | 🟡 via API + integrations | ✅ Free | ✅ Phase 2 must-have |
| **API keys / OAuth apps** | ✅ | ✅ Free | ✅ Phase 1 (driven by C2) |
| **Audit log** | 🟡 via mail.thread message_post | 🟡 paid tiers | ✅ Phase 2 (compliance + debugging) |
| **Reports / Dashboards** | ✅ pivot view | ✅ Free (canned) | ⚠️ Phase 3 |

### 4.2 Phasing — Now / Next / Later

#### **NOW (Phase 1 — foundations, ~4–6 weeks of focused work)**
*Goal: a deployable, secure, multi-tenant base that can accept one real lead-capture widget on one KMOSF client site end-to-end.*

- **Tenancy.** `TenantScopedDocument` base + `tenantId` on every doc + `TenantContext` `ContextView` in Reactor + `WebFilter` that populates it from the principal.
- **Auth.** Spring Security WebFlux + JWT for first-party, hashed API keys for server-to-server, signed embed tokens for widgets. Decision needed — see §7.
- **Error envelope.** `@RestControllerAdvice` → RFC 7807 Problem-Details. Wire `DigiPresBeException` and `WebExchangeBindException` through it.
- **Domain v0.** `Contact` (Person), `Account`, `Lead`, plus the existing `EmailContact` re-modeled as a value object embedded in `Contact`.
- **Lead-capture endpoint.** `POST /api/v1/widgets/leads` — accepts an embed-token-authenticated payload, validates, creates `Lead`, emits a domain event.
- **OpenAPI.** Add springdoc, commit generated spec, lock the contract.
- **Audit fields + auditing.** `@EnableReactiveMongoAuditing`, `@CreatedDate`, `@LastModifiedDate`, `@CreatedBy`, `@LastModifiedBy`.
- **Mongo conversions.** Custom `Converter` for `InternetAddress` + `MongoCustomConversions` bean.
- **GlobalExceptionHandler + first integration test** that proves an invalid payload yields the right Problem-Details envelope.

#### **NEXT (Phase 2 — make it a CRM, ~6–10 weeks)**
*Goal: feature parity with HubSpot Free for the basic "track contacts and deals" use case, plus the multi-tenant + custom-fields machinery that KMOSF's client model requires.*

- **Pipelines, Stages, Deals.** Configurable per tenant; one default pipeline seeded on tenant creation.
- **Activities & Notes.** Polymorphic `Activity` (call, email, meeting, note) timeline per Contact and per Deal.
- **Custom fields per tenant.** Stored as a `Map<String, Object>` on each entity plus a per-tenant `CustomFieldSchema` collection that defines types and validation rules. (Mongo's schemaless nature makes this much cheaper than in a relational DB.)
- **Inbound email ingestion.** Use `spring-integration-mail` (already on the classpath at `build.gradle:46`) to poll IMAP and ingest into a `EmailMessage` collection linked to `Contact`/`Deal` by address.
- **Meetings end-to-end.** Add `MeetingService`, ICS generation, invite emails via existing `EmailService`.
- **Webhooks out.** Configurable per tenant; retry with exponential backoff via Resilience4j (already on the classpath, finally gets used).
- **Quartz jobs.** Activate the dependency for: scheduled-send emails, follow-up reminders, expiring embed-token cleanup.
- **Tasks.** Lightweight, assigned to a user, linked to any entity.
- **Audit log.** Append-only collection, partitioned by tenant + date.
- **Idempotency keys.** Generic implementation in a `WebFilter`.
- **First admin UI cuts over.** API has to be ready for CRUD-heavy views by end of this phase.

#### **LATER (Phase 3 — differentiate, ~10+ weeks)**
*Goal: features that justify "use KMOSF instead of paying for HubSpot/Odoo."*

- **Quotes & Proposals.** PDF generation, e-sign integration (DocuSign / Dropbox Sign API).
- **Helpdesk / Tickets.** Same data backbone as activities; SLA tracking.
- **Marketing automation (lightweight).** Trigger-based email sequences. Don't try to out-feature HubSpot Workflows — match the 80% case.
- **Reporting & dashboards.** Pre-computed aggregates in Mongo, served via a reporting API the admin UI consumes.
- **Per-client tenant SaaS-ification.** If KMOSF clients want their own logo/colors/domains for *their* customers seeing CRM-driven pages (preference center, ticket portal, etc.), this is where white-labelling happens.
- **Eventing backbone.** Kafka (the commented `org.apache.kafka:kafka-streams` line at `build.gradle:43`) for cross-service events. Until then, an in-process `ApplicationEventPublisher` + outbox pattern is enough.
- **AI features.** Given your job is transitioning to AI agent work, this is the natural extension: lead scoring, summarization of activity timelines, draft-response generation. **Don't try to ship this in Phase 1 or 2** — the boring CRM has to work first.

### 4.3 KMOSF-Specific Differentiators

Three places where you can out-build HubSpot/Odoo for *your* use case without taking on their entire scope:

1. **Embeddable widgets as a first-class product surface.** Not a clunky iframe afterthought, but typed JS SDKs (npm package) and bare-HTML snippets. Forms, chat, scheduling, ticket-submission. This is the "we hand you a `<script>` tag and it Just Works" pitch.
2. **Per-client tenant onboarding automation.** When KMOSF stands up a new client, the CRM tenant should be provisioned the same way the website is — one workflow, not a separate manual step. This means an admin API for tenant creation, default-pipeline seeding, and embed-token issuance.
3. **Tight coupling to the website-building side of the business.** Domain registration, hosting, and the website CMS already touch the client's data. The CRM gets to *start* with that context (contact form submissions become Leads automatically, page-view events become Activities, etc.) instead of being a separate silo.

---

## 5. Phase 1 Spec (Next ~4–6 Weeks)

This is what I'd open as the first plan / branch. It's intentionally tight — each item is small enough to ship in a day or two for someone with your Spring background.

### 5.1 Branch shape

Per the workspace branching convention (`../../CLAUDE.md` from the repo): one plan-slug branch for the whole Phase 1 if it ships as one cohesive PR, or split into `phase-1-<desc>` branches per checkpoint below. I'd lean **split** — each checkpoint has a clean test gate and reviewing 4–6 weeks in one PR is brutal.

### 5.2 Checkpoint sequence

#### **CP-1: Cleanup pass (1–2 days)**
- Prune `.claude/worktrees/`.
- Update `CLAUDE.md` to remove the stale "hardcoded password" warning; add a section on `KMOSF_MAIL_SMTP_*` env vars.
- Fix `compose.yaml` port mapping (`'27017:27017'`) and DB-name case (`kmo-digipres-be`).
- Delete `@Component` from `EmailUtil`.
- Either delete `SchedulingController` or give it a real `@RequestMapping`.
- Add `@Valid` + Jakarta Bean Validation annotations to `SingleEmailCommunicationDTO`.

#### **CP-2: Error envelope + exception handler (1–2 days)**
- Define `ProblemDetail` record (or use Spring 6's built-in `org.springframework.http.ProblemDetail`).
- `@RestControllerAdvice GlobalExceptionHandler` covering: `DigiPresBeException`, `WebExchangeBindException`, `ResponseStatusException`, `Throwable`.
- Add `traceId` to every Problem-Details response (pulled from MDC / Reactor context).
- Integration test: malformed JSON yields 400 with the right shape; unauthenticated request yields 401; internal error yields 500 with `traceId` but no stack trace.

#### **CP-3: Tenancy primitive (3–5 days)**
- `abstract class TenantScopedDocument` with `id`, `tenantId`, audit fields, `@Version`.
- `@EnableReactiveMongoAuditing` on `KmoDigipresBeApplication`.
- `TenantContext` static accessor backed by Reactor context (`TenantContextHolder.current()` returns `Mono<TenantId>`).
- `TenantWebFilter` that resolves `tenantId` from the auth principal and writes it to the Reactor context.
- `TenantScopedRepository` interface that extends `ReactiveMongoRepository` and overrides save/find methods to inject the tenant filter.
- Migrate `Meeting` to the new base.
- Test: a request for tenant A cannot read documents for tenant B even with the same ID.

#### **CP-4: Auth baseline (3–5 days — depends on §7 decision)**
- Add `spring-boot-starter-security` and `spring-boot-starter-oauth2-resource-server`.
- For first-party (admin UI, future KMOSF console): JWT validation against a chosen IdP (Keycloak self-hosted, Auth0, or roll-your-own — decision in §7).
- For server-to-server: API-key filter, hashed at rest (Argon2id), scoped to a tenant.
- For embed widgets: short-lived signed JWT issued by the API, carrying `(tenantId, widgetId, scope)`.
- `SecurityFilterChain` config; `@PreAuthorize` available; default-deny on every controller route.
- Test: every endpoint returns 401 without auth; 403 with wrong tenant.

#### **CP-5: Domain v0 (3–5 days)**
- New `model/contact/Person.java` (the real human-being entity, separate from the `Contact` channel-address marker — rename `Contact` → `CommunicationAddress` to break the conceptual overload).
- `model/account/Account.java` for organizations.
- `model/lead/Lead.java` with `source`, `status`, `score` (placeholder), `attachedTo` (Person/Account ref).
- Repositories extending the tenant-scoped base.
- Custom `Converter<InternetAddress, String>` + `MongoCustomConversions` bean.

#### **CP-6: First widget endpoint (2–3 days)**
- `POST /api/v1/widgets/leads` — accepts an embed-token-authenticated `LeadCaptureDTO` (name, email, message, custom fields).
- Validates, creates `Lead`, publishes a `LeadCreatedEvent` (in-process `ApplicationEventPublisher` for now).
- Optionally fires off a notification email via the existing `EmailService` — this is where the existing vertical slice finally gets to do real work.
- Idempotency via `Idempotency-Key` header.

#### **CP-7: OpenAPI + docs (1–2 days)**
- Add `springdoc-openapi-starter-webflux-ui` and `springdoc-openapi-starter-webflux-api`.
- Configure: title, version, servers, security schemes.
- Commit `docs/api/openapi.json` (generated).
- Add a Gradle task that re-generates it and fails CI if drift is detected.

#### **CP-8: Integration test bar (ongoing)**
- Every controller endpoint has a WebTestClient integration test against Testcontainers Mongo.
- Tests run in CI on every push to a feature branch.
- This is the "do testing for the user" promise from the preferences — testing should be a Claude-driven floor that's met before any PR review starts.

### 5.3 Phase 1 Acceptance Criteria

Phase 1 is done when **all** of these are true:
- A real KMOSF client website embeds the lead-capture widget and the lead lands in MongoDB under that client's tenant.
- An unauthenticated `curl` to any endpoint returns a 401 with a Problem-Details body.
- A request for tenant A's data with tenant B's credentials returns 403, not 200-empty.
- `GET /openapi.json` returns a spec that covers every live endpoint.
- `./gradlew build` runs zero `Resilience4j` warnings, has at least 70% line coverage on the controller + service layer, and produces a docker image.
- `CLAUDE.md` is updated to reflect the new tenancy primitive, auth model, and error envelope.

---

## 6. Open Questions (Need Owner Input Before Phase 1 Starts)

These are the genuinely-needs-a-decision items. Don't proceed past CP-3 without answering them — wrong answers here are expensive to undo.

### Q1: Identity provider?
Options: (a) self-hosted Keycloak (free, you own the data, ops cost), (b) Auth0 / Stytch / Clerk (paid, much less ops, but recurring cost adds friction to the "we host CRM so clients don't pay Odoo monthly" pitch), (c) roll your own with `spring-boot-starter-security` + Mongo-backed user store (cheapest, biggest surface area to get wrong).
- **My lean:** self-hosted Keycloak. Free, KMOSF-controlled, the operational overhead is one Docker container, and it's the standard answer in the Spring world so the docs/community are deep.

### Q2: Tenant resolution strategy?
Options: (a) subdomain (`acmecorp.crm.kmosf.com`), (b) path prefix (`/api/v1/tenants/acmecorp/...`), (c) header-only (`X-Tenant-Id`), (d) embedded in the JWT claim.
- **My lean:** combine (a) for first-party admin UI and (d) for API-key + embed-token callers. (b) is ugly. (c) alone is too easy to spoof.

### Q3: One Mongo cluster or shared-vs-isolated tenant data?
Options: (a) single cluster, tenant column on every doc (cheapest, most ops-able, what HubSpot does), (b) database-per-tenant (better isolation, harder to operate, painful migrations), (c) cluster-per-large-tenant (hybrid).
- **My lean:** (a) for all but the largest tenants; reserve (c) as a future option for enterprise clients. This decision shapes the `TenantScopedDocument` design — (a) is what I assumed above.

### Q4: Embed-token signing?
Options: (a) symmetric (HMAC-SHA256) per-tenant secret, (b) asymmetric (Ed25519) with rotating per-tenant key pairs.
- **My lean:** (b). Symmetric keys leak through stack traces, logs, frontend bundles; rotation is annoying. Ed25519 + public key in the JS SDK is cleaner.

### Q5: How aggressively to lean on Mongo's schema flexibility?
Options: (a) treat Mongo like a relational DB with strict per-collection schemas (use `MongoTemplate` validation, Mongock for migrations), (b) embrace polymorphic schemaless storage especially for custom fields and activity types.
- **My lean:** (b) for custom fields and activities (it's literally what Mongo is good at), (a) for everything else (the trade-off of "no schema = no safety" hurts most for core entities).

### Q6: Does KMOSF want a customer-facing portal (clients' clients log in to see *their* data) in Phase 3, or is the customer-facing surface always-embed-widget?
- This shapes whether you build a third auth tier (end-user / consumer accounts) on top of the existing two. Cheaper to know early.

### Q7: Anthropic plug-in / agent angle?
- You mentioned your job is transitioning to AI agent work. There's a natural "ship a Claude plugin for this CRM" play once Phase 2 is done — the CRM's API becomes a tool surface that agents can call. Worth noting now as a North Star so design decisions (especially API ergonomics and OpenAPI quality) account for "an LLM will read this" alongside "a human will read this."

---

## 7. Suggested Immediate Next Step

Pick **one** of the following — I'd recommend the first:

1. **Answer Q1–Q5** so we can start CP-1 with no architectural ambiguity. If you want, I can put each open question into its own elicitation card so you can answer them one at a time without losing context.
2. **Just do CP-1 first** (the cleanup pass) — it's safe to ship regardless of how Q1–Q5 land, and it gives us a clean baseline to plan against.
3. **Convert this doc into a series of plan files** (one per checkpoint) so you can work them off the workspace branching convention without re-reading this whole doc each time.

— End of audit —
