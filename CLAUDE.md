# CLAUDE.md — kmo-digipres-be

This file provides guidance to Claude Code when working with code in this repository.

## Repository Overview

`kmo-digipres-be` is the backend for the **KMOSF CRM** — a custom multi-tenant CRM / back-office platform built for KMO Solutions Foundry LLC. The "digipres" name and `com.kumouri` Java package are historical; treat any "digipres" / "kumouri" references as synonyms for the KMOSF CRM — not a separate product.

This is a **near-complete CRM / back-office platform** with multi-tenant scoping, auth, contacts, companies, deals, activities, billing, quotes + PDF, invoices + payments, product catalog, S3 storage, Twilio SMS, Postmark transactional email, inbox, email sequences, workflow automation, custom field definitions, module registry, portal auth, AI assist, RAG retrieval, lead scoring v2, GDPR compliance, reporting, service hub, home-services vertical, restaurant-light module, salon/spa module, CSV imports, and public widget endpoints. For the full architectural overview and roadmap, see `docs/design/01-architecture-audit-and-crm-roadmap.md` (untracked — present in working tree; do not delete or commit without explicit instruction).

## On-demand reference files

When running, building, or testing locally, read `.claude/commands.md`.
When navigating packages or understanding subsystem responsibilities, read `.claude/architecture.md`.
When writing or running tests, read `.claude/testing.md`.
When choosing what to build next or checking if a feature exists, read `.claude/roadmap.md`.

## Stack

- **Spring Boot 3.5.6** on **Java 21** (toolchain pinned in `build.gradle`)
- **Reactive end-to-end**: `spring-boot-starter-webflux` (Netty), `spring-boot-starter-data-mongodb-reactive` with `ReactiveMongoRepository`, reactive Resilience4j. All controllers return `Mono`/`Flux`; any blocking I/O (JDBC, `jakarta.mail.Transport.send`, PDF generation) is wrapped in `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`. Do not introduce blocking patterns on Netty event-loop threads.
- **Jakarta Mail** through a hand-rolled `Session` bean (`AngusConfig`) talking to ProtonMail SMTP — *not* `JavaMailSender`
- **Spring's `@Scheduled`** is in active use (ReportScheduler, SlaBreachScheduler, SequenceEngine, etc.). **Quartz** is on the classpath but not yet wired — stop-gap only; see roadmap.
- **MapStruct 1.6.3** + **Lombok** as annotation processors. Order in `build.gradle` matters — don't reorder the `annotationProcessor` block without verifying MapStruct still generates implementations.
- **Spring Cloud Circuit Breaker (Resilience4j, reactor)** is in active use: `WebhookDeliveryService` and `QuickBooksInvoiceSync` both use per-subscription circuit breakers.
- **Nimbus JOSE + JWT** for HS256 JWT signing/verification (self-issued; `kmosf.auth.mode` switches to Zitadel JWKS decoder in Phase A).

`build.gradle` carries commented-out starters (Kafka, Spring Integration MongoDB, session-data-mongodb, OTLP registry). These are aspirational scaffolding — don't delete them when cleaning up.

## Architecture overview

Layered Spring WebFlux structure under `com.kumouri.kmodigipresbe`. All tenant-owned repositories extend `TenantScopedSimpleReactiveMongoRepository` (not bare `ReactiveMongoRepository`) — this auto-stamps and auto-filters by `tenantId`. See `.claude/architecture.md` for the full package overview and conventions.

## Safety rules (apply every session)

- **SMTP credentials are externalized** via `@Value` in `AngusConfig`. Never hardcode credentials. The app fails to start if `KMOSF_MAIL_SMTP_PASSWORD` is absent — this is intentional.
- **JWT secret** (`KMOSF_JWT_SECRET`) must be >= 32 bytes for production. If unset, a random ephemeral key is generated and a WARN is logged — tokens invalidate on every restart; not suitable for production.
- **Blocking I/O must use `Schedulers.boundedElastic()`** — never call blocking code on a Netty event-loop thread.

## Branching

This repo follows the workspace branching conventions (see `../../CLAUDE.md`): `<plan-slug>` for single-phase plans, `<plan-slug>-phase-N-<desc>` only when one plan is split, `fix/<desc>` and `feat/<desc>` for unplanned work. Default branch is `main`; don't work directly on it.
