# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Overview

`kmo-digipres-be` is the backend for a custom CRM system being built for **KMO Solutions Foundry LLC (KMOSF)**. The "digipres" name and `com.kumouri` Java package are historical: the project was scaffolded when the owner was still considering splitting KMOSF into two entities (one for solutions/consulting, one for digital presence management). The CRM stayed as a single project after that decision was reversed, but the naming was not refactored. Treat any "digipres" / "kumouri" references as synonyms for the KMOSF CRM — not a separate product.

This is the **start** of the system. As of the initial commit, only the single-email communication path is wired end-to-end (`POST /api/communication/singleEmail`). Most other surface area (scheduling, contact persistence, meeting management) is scaffolded but unimplemented.

## Stack

- **Spring Boot 3.5.6** on **Java 21** (toolchain pinned in `build.gradle`)
- **Reactive end-to-end**: `spring-boot-starter-webflux` (Netty) on the web side, `spring-boot-starter-data-mongodb-reactive` with `ReactiveMongoRepository`, and reactive Resilience4j Spring Cloud Circuit Breaker on the classpath. All controllers return `Mono`/`Flux`; all repositories extend `ReactiveMongoRepository`; any blocking I/O (e.g. `Transport.send` in `EmailService`) is wrapped in `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`. Do not introduce blocking patterns — see "Reactive conventions" below.
- **Jakarta Mail** through a hand-rolled `Session` bean (`AngusConfig`) talking to ProtonMail SMTP — *not* `spring-boot-starter-mail`'s `JavaMailSender`
- **Quartz** for scheduling (declared, not yet used)
- **MapStruct 1.6.3** + **Lombok** as annotation processors. Order in `build.gradle` matters; don't reorder the `annotationProcessor` block without verifying MapStruct still generates implementations
- **Spring Cloud Circuit Breaker (Resilience4j, reactor)** declared but not yet applied anywhere

`build.gradle` carries a number of **commented-out starters** (security, Kafka, Spring Integration MongoDB, session-data-mongodb, docker-compose, OTLP registry). These are aspirational scaffolding for where the project is heading — don't delete them when cleaning up, and don't assume their features are available.

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

`compose.yaml` defines a Mongo service but **does not map the host port** (`- '27017'` exposes a random host port). `application.properties` hardcodes `mongodb://localhost:27017/`. If you want to use `docker compose up` for local Mongo, either change the compose mapping to `'27017:27017'` or point the app at the random host port.

Also note the **db name case mismatch**: `compose.yaml` sets `MONGO_INITDB_DATABASE=KMO_DIGIPRES_BE` (uppercase) but the app connects to `kmo-digipres-be` (lowercase-hyphenated). MongoDB db names are case-sensitive — the app will create its own db on first write rather than using the one the init script created.

## Architecture

The codebase follows a layered Spring WebFlux structure under `com.kumouri.kmodigipresbe`:

- `controller/` — REST entry points. `CommunicationController` is the only live one; `SchedulingController` is an empty stub.
- `service/` — Business logic. `ContactService<T extends CommunicationRequest>` is the generic top-level interface; `EmailService` is its first implementation. The intent is one service per outbound channel (email, eventually SMS, calendar, etc.) all funneled through `initiateContact`.
- `model/contact/` — `Contact` (marker interface, annotated `@Document` so Mongo can persist any implementation) and concrete `EmailContact` record.
- `model/meeting/` — `Meeting` mongo document with `organizer` + `attendees` typed as the `Contact` interface.
- `model/request/` — Two parallel hierarchies:
  - **DTOs** (`CommunicationDTO`, `SingleEmailCommunicationDTO`) — flat string-typed payloads coming in over HTTP
  - **Requests** (`CommunicationRequest`, `EmailCommunicationRequest`, `SingleEmailCommunicationRequest`) — internal types where `to`/`from` are already parsed `Contact` instances
  `RequestMapper` (MapStruct) is the boundary between them; a `default` method on the mapper turns email strings into `EmailContact` instances and is how `String → InternetAddress` parsing happens. Add new channel DTOs in pairs and extend `RequestMapper` accordingly.
- `repository/` — Reactive Spring Data Mongo repositories (`ReactiveMongoRepository<T, UUID>`). All new repositories should follow the same pattern.
- `config/AngusConfig` — Provides the `jakarta.mail.Session` bean. **The ProtonMail SMTP password is currently hardcoded in source** (`AngusConfig.java:24`) — this needs to be externalized to `application.properties` / env vars / a secret store before any non-trivial work lands. Flag this in PR reviews; do not propagate the pattern.
- `util/EmailUtil` — Static `String → InternetAddress` parser that wraps `AddressException` in `DigiPresBeException`.
- `exceptions/DigiPresBeException` — App-wide `RuntimeException` carrying an `errorCode` and `httpStatusCode`. There is currently no `@ControllerAdvice` translating it to an HTTP response — exceptions will bubble out as 500s until one is added.

### Reactive conventions

- Controllers return `Mono`/`Flux`. `@RequestBody` parameters can be plain DTOs or `Mono<DTO>` — pick the plain form unless streaming or upstream-deferred validation is needed.
- Repositories extend `ReactiveMongoRepository`.
- **Any blocking I/O** (JDBC, `jakarta.mail.Transport.send`, blocking HTTP clients) must be wrapped in `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`. `EmailService.sendSingleEmail` is the reference pattern. Never call blocking code directly on a Netty event-loop thread.
- The base path comes from `spring.webflux.base-path=/api` in `application.properties`. The servlet-style `server.servlet.context-path` is silently ignored under WebFlux — do not use it.

### REST conventions

Server runs on port **8080** with base path **`/api`** (via `spring.webflux.base-path`), so `CommunicationController`'s `/communication/singleEmail` resolves to `POST /api/communication/singleEmail`. The controller currently returns `Mono<Boolean>` directly — there's no envelope/response-object convention yet.

## Testing

Tests use Spring Boot Test with **Testcontainers** for Mongo and Kafka (`TestcontainersConfiguration`). Kafka is wired into the test container set even though the main app's Kafka dependencies are commented out — this is intentional scaffolding; do not remove the Kafka testcontainer when Kafka isn't in scope unless you also remove the commented Kafka deps.

`TestKmoDigipresBeApplication` is a `main`-method entry point for running the real app against Testcontainers-managed infrastructure — use it (or `./gradlew bootTestRun`) for local manual testing when you don't want a real Mongo running.

REST Docs is configured (`spring-restdocs-webtestclient` + asciidoctor plugin). When endpoints are documented, snippets land in `build/generated-snippets` and the `asciidoctor` task assembles them.

## Branching

This repo lives under `kmosf/repos/` in the KMOSF workspace and follows the workspace branching conventions (see `../../CLAUDE.md`): `<plan-slug>` for single-phase plans, `<plan-slug>-phase-N-<desc>` only when one plan is split, `fix/<desc>` and `feat/<desc>` for unplanned work. Don't work directly on `master`.
