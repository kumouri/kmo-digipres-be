# Feasibility & Recommendation Memo: Single-Tenant Conversion + Tenant-Provisioning Orchestration

**Repo:** `kmosf/repos/kmo-digipres-be`
**Authored:** 2026-05-15 · **Type:** research/decision memo (no code in this pass — per the chosen deliverable)
**Plan slug:** `research-what-it-cozy-backus`
**Notion (canonical):** <https://www.notion.so/362eaa56f92e812091fad24d8e3dda3d> — nested under the "Build FOSS back office solution for KMO clients" task.
**Relates to:** the locked back-office ultraplan `back-office-iridescent-wilkes`.

> **Doc location note:** `.claude/` is gitignored in `kmo-digipres-be` (`.gitignore:40`), so this tracked ADR-style memo lives in `docs/design/` (the ultraplan's designated ADR home), *not* `.claude/`. This corrects the memo's own earlier §8 suggestion.

---

## 1. Context

The question asked: *what would it take to convert the architecture from multi-tenant to single-tenant, plus an orchestration layer that makes it easy to spin up new tenants* — with an initial lean toward containers on AWS EKS + an admin intake UI that templates per-tenant config and deploys via AWS CLI, explicitly flagged as "day-job familiarity, 100% open to better."

This pass produces a **feasibility + recommendation memo** (not code, not an executable plan): research **both** code-conversion approaches, compare **all** orchestration options honestly, scope orchestration **CRM-now / full-stack-ready-later**, and **reconcile explicitly with the locked back-office ultraplan** (`back-office-iridescent-wilkes`) rather than silently overriding it.

This matters because the request overlaps two locked strategic decisions in that ultraplan:
- **§2 Multi-tenancy posture (locked):** Pattern A → Pattern C. Per-client instances of the *multi-tenant-capable* code; KMOSF is "client zero, same architecture"; **avoid Pattern B** (shared multi-tenant SaaS) — it triggers AGPL source-disclosure exposure for the bundled OSS apps.
- **§8 D2 (locked):** "Docker Compose → Coolify. **Don't run K8s for <20 tenants.**" Solo-founder ceiling 8–12 tenants.
- **§6 Phase I (deferred until first paying client funds it):** a new `kmo-digipres-deploy` repo = Terraform/Ansible + per-tool Compose templates + `bootstrap.sh`.

The headline finding: **the goal is fully achievable without either the code amputation or EKS.** "Convert to single-tenant" is best reframed as **a single-tenant *deployment mode*** (a reversible config flag, ~S effort) and **per-VPS provisioning** (the already-planned Phase I, scoped to CRM-first).

---

## 2. What exists today (verified against source)

**Tenancy is row-level and centralized — not scattered.** Shared Mongo collections; a `tenantId` UUID on tenant-scoped entities. **No database-per-tenant** (`config/MongoConfig.java` has zero `ReactiveMongoDatabaseFactory` customization — only `repositoryBaseClass` + converters). Enforcement lives in ~6 `tenancy/` classes:

- `TenantWebFilter` (`@Order(DEFAULT_FILTER_ORDER+1)`) resolves tenant from the JWT and `.contextWrite(TenantContextHolder.write(tenant))`.
- `TenantContext` (record: tenantId, userId, roles) in Reactor Context key `kmosf.tenantContext`; `TenantContextHolder.current()/required()/write()`. **Never ThreadLocal** (arch-test enforced).
- `TenantStampingCallback` (`ReactiveBeforeConvertCallback<TenantScoped>`) — stamps tenantId on save, rejects foreign tenantId (1003/1004). **No-op for non-`TenantScoped` types.**
- `TenantScopedSimpleReactiveMongoRepository` — base repo wired globally via `MongoConfig`; injects `Criteria.where("tenantId").is(...)` into reads/counts/deletes **only when the entity is `TenantScoped`**.
- `TenantScoped` marker; `Auditable extends TenantScoped`. `RoleGuard` (roles only, no tenantId). `FieldPermissionPolicy` (per-tenant).
- `Tenant` model (not tenant-scoped); `TenantBootstrapController`/`Service` (`POST /api/tenants`, `X-Bootstrap-Token` → creates Tenant + admin User; 409 on duplicate slug); `DataSeeder` (dev); `TenantModuleRegistry` (two-stage: global bean presence + `Tenant.enabledModules`).

**Decisive fact:** stamping + read-criteria are *entirely Reactor-context driven and no-op off the `TenantScoped` path*. The isolation logic does not care **where** the `TenantContext` comes from — so a single fixed context makes the entire machinery behave single-tenant with zero model/repo/query churn.

**Measured blast radius (for the strip option):** ~41 classes implement `TenantScoped`/`Auditable`; ~52 model files carry `tenantId`/`@CompoundIndex`; ~35 repos + ~69 `...ByTenantId...` derived queries; ~28 files call `TenantContextHolder.write(...)` (webhooks, schedulers, public widgets, Stripe/QuickBooks/Postmark, portal pre-auth); ~62 of 86 test files touch tenancy; a second arch test (`ArchUnitVectorTenancyTest`) *structurally mandates* `UUID tenantId` as the first param of `VectorIndex.search`/`EmbeddingService.embed`.

**Deployment baseline ≈ zero IaC.** No Dockerfile; image only via `./gradlew bootBuildImage` (Paketo buildpacks). CI builds/tests only — no registry push, no deploy. `compose.yaml` is a dev-only Mongo. **All per-tenant-varying config is already env-externalized** in `application.properties` (`spring.data.mongodb.uri`/`database`, SMTP, `KMOSF_JWT_SECRET`, `KMOSF_CORS_ALLOWED_ORIGINS`, S3 bucket/endpoint/keys, `KMOSF_PORTAL_BASE_HOST`, WebAuthn rp-id/origins, cookie domain, `KMOSF_MODULE_*`). Tenant subdomain resolution is host-based (`acme.crm.kmosf.dev` → `acme`). The in-app provisioning seam (`TenantBootstrapController`) already exists.

---

## 3. Part 1 — Code conversion: Approach A vs B

| | **A — Single-tenant mode (keep machinery, pin one tenant)** | **B — Full strip (amputate tenancy)** |
|---|---|---|
| **Shape** | Add `kmosf.tenancy.mode=single\|multi`. One new `@ConditionalOnProperty("single")` `WebFilter` at the same order as `TenantWebFilter` that writes a fixed `TenantContext(configuredTenantId, jwtUid, jwtRoles)`; gate the existing filter `multi`. Provision one `Tenant` row at startup via existing `TenantBootstrapService`. | Remove `tenantId` from ~41 classes; delete `TenantScoped`/`TenantContext`/`TenantContextHolder`/`TenantStampingCallback`/resolvers; revert `MongoConfig` to stock repo; rewrite ~69 derived queries + call sites across 80 controllers/83 services; redesign every `tenantId`-prefixed `@CompoundIndex`; rework ~28 synthetic-context sites; collapse `TenantModuleRegistry`/`FieldPermissionPolicy`/JWT `tid`; delete/rewrite ~62 tenancy tests + invert an arch test. |
| **Effort** | **S** — ~3–6 files. No model/repo/query/index/test churn. | **XL** — ~250+ files, multi-week, every shipped phase, long compile-error tail. |
| **Risk** | Filter ordering (mitigated by reuse + conditional exclusivity); startup race (readiness gate); pre-auth `HostTenantResolver` paths (fail-closed to configured slug). All low/contained. | Silent data exposure during partial rollout; index-redesign breakage on shipped collections; regression across 14 phases with the safety net deleted; loss of a deliberate leak-guard arch test. Several **high**. |
| **Reversibility** | **Trivial** — flip a flag. No schema/data change. | **Effectively irreversible** — re-adding row-level tenancy means redoing everything + backfilling historical `tenantId`. |
| **Strategic fit** | **Is** Pattern A→C: one artifact everywhere, behavior by config; KMOSF stays "client zero, same code"; defense-in-depth retained for free. | **Self-defeating** under the locked plan: KMOSF's instance no longer runs the same code as client instances; forecloses dogfooding rationale and any future consolidation. |

**Recommendation: Approach A, decisively.** B's only honest upside is code simplicity, and that is marginal because tenancy is *already* centralized in ~6 classes — single-tenant *mode* delivers the operator-facing simplicity (one tenant, one JWT shape) without an irreversible XL amputation that deletes the test safety net and breaks the plan's foundational principle. Revisit B only if Pattern A→C is itself abandoned (a strategy decision, not a code decision).

---

## 4. Part 2 — Orchestration: options compared + provisioning design

**The Swarm-vs-EKS conflation, addressed first.** "Edit a Docker Swarm config, then `aws` deploy to EKS" splices two unrelated orchestrators — Swarm runs a `docker stack` on plain Docker hosts with no control plane; EKS is managed Kubernetes applied via `kubectl`/`helm`. You cannot apply a Swarm config to EKS. What the workflow you *like* actually is: **a declarative per-tenant config that an automated step turns into an isolated running deployment.** Every option below delivers that shape; the day-job EKS has a devops team absorbing its cost — a solo founder does not.

**Cost (USD/mo, infra only; ~$5 backup + ~$15 email per tenant are option-independent):**

| Option | 1 | 3 | 8 | Always-on tax |
|---|---|---|---|---|
| **(a) AWS EKS + admin UI** | ~$153 | ~$213 | ~$360 | **~$73 EKS control plane at zero tenants** + NAT ~$32 + ALB ~$18 |
| **(b) Coolify + Hetzner + Compose** (ultraplan-locked) | **$30** | **$90** | **$240** | $0 |
| (c1) ECS/Fargate | ~$85 | ~$155 | ~$347 | $0 CP but NAT/ALB quasi-fixed |
| (c2) single-node K3s/VPS | $30 | $90 | $240 | $0, but you run K8s solo |
| (c3) Docker Swarm/VPS · (c5) plain Compose/VPS | $30 | $90 | $240 | $0 |

At 1 tenant EKS is **~5×** Hetzner; even at 8 it's ~1.5× **and** you still owe Kubernetes ops. Decisive non-cost axes: **AGPL/Pattern-A** — one-VPS-per-tenant options are Pattern-A *by construction*; shared clusters make the forbidden Pattern B the path of least resistance. **Stack-ready-later** — the ultraplan's Phase I bundle *is* per-tool Compose + Caddy; adding Zitadel/Cal.com/etc. later is appending services to the same Compose file, vs. re-expressing 10 apps as K8s/ECS manifests. **Isolation** — separate VPS gives full host/network/AGPL isolation for free; clusters need deliberate namespace/network-policy work to match it. **The GUI/declarative draw is satisfied by Coolify** (Git-push deploys, env management, auto-TLS, backups) at $30/VPS with no control-plane fee.

**Provisioning + admin-intake design (orchestrator-agnostic):**
- **Intake form (admin-only):** display name; **slug** (DNS-safe, immutable → subdomain + `Tenant.slug` + S3 prefix + Mongo db `kmo-digipres-<slug>`); domain ownership (D3: client owns apex); modules → `Tenant.enabledModules` + `KMOSF_MODULE_*`; email (Postmark house vs. own); region/residency (Hetzner location); backup target (B2); admin email/name (seeds bootstrap user).
- **Map intake → one rendered per-tenant `.env`** (the source of truth) covering the already-externalized vars; generate fresh per-tenant secrets (`KMOSF_JWT_SECRET`, `KMOSF_BOOTSTRAP_TOKEN`, `KMOSF_WIDGET_TOKEN_SECRET`, Mongo password, S3 keys).
- **Steps:** validate slug → render env + Compose/Caddy from template → create infra (VPS) → deploy buildpack image + Mongo + Caddy → DNS + Let's Encrypt → **bootstrap tenant row** (`POST /api/tenants` w/ `X-Bootstrap-Token`) → smoke test (health, admin login, one authenticated read). Idempotent per slug (Tenant create already 409s on duplicate); resumable from last successful step.
- **Secrets (minimum viable, no Secrets Manager):** per-tenant secrets generated at render time, written only to that tenant's VPS (mode 600, never Git); orchestrator host keeps one encrypted store (SOPS+age / `pass`) for provider tokens + DR registry. Sufficient at 1–8 tenants.
- **Off-boarding:** Mongo dump + S3 prefix → dated tarball to client; `Tenant.status` terminal; destroy VPS + DNS; retain encrypted backup for the contractual window. (Phase I already specifies this.)
- **Reuse:** `TenantBootstrapController`/`Service`, full env externalization, S3 prefixing, `bootBuildImage`. **Net-new:** image registry push (keep buildpacks — no Dockerfile needed), per-tenant Compose+Caddy templates, config renderer + `bootstrap.sh`, DNS/TLS automation, Restic, a **thin** intake surface (CLI/script first; a small `kmo-digipres-fe` admin page only if a human form is wanted — *not* a heavyweight new app).

**Recommendation: stay the locked Coolify/Hetzner course; do not override D2 toward EKS.**

---

## 5. CRM-now / full-stack-ready-later structuring

Make the **deploy unit a per-tenant directory = one Compose file + one rendered `.env` + one Caddy config**, not a CRM-specific script. v1 ships **two services (kmo-digipres-be + Mongo) + Caddy** (optionally the FE). The full ultraplan bundle is added later by **appending services** to the same Compose file and **site blocks** to the same Caddy config — no rearchitecture, because (a) config is already env-per-tenant and every Foundry app configures the same way; (b) the DNS pattern `<tool>.<slug>.crm.kmosf.dev` already generalizes (CRM is just the first `<tool>`); (c) the deploy unit (one VPS / one Compose project / one Caddy) is exactly the ultraplan's target bundle shape, scoped down. Keep the renderer template as a "list of services" from day one so adding Zitadel/Cal.com/Documenso is **data, not redesign**.

---

## 6. Reconciliation with the locked ultraplan

Both recommendations **honor the locked decisions** rather than amend them:

- **§2 Pattern A→C, AGPL/Pattern-B avoidance:** Approach A *is* Pattern A→C (one multi-tenant-capable artifact, single-tenant by config). One-VPS-per-tenant is Pattern-A by construction.
- **§8 D2 "no K8s <20 tenants":** upheld — Coolify/Hetzner/Compose, not EKS.
- **§6 Phase I (`kmo-digipres-deploy`, deferred until first paying client):** this work **slots under Phase I as an explicit narrowing of its first deliverable** — "CRM + Mongo + Caddy per Hetzner VPS, structured for the full bundle" — *not* a change to D2/§2. Phase I's timing rule (deferred until the first sale funds it) is unchanged; the single-tenant *mode* code change (Part 1, S effort) is the only piece that can sensibly land earlier, since it's a reversible flag with standalone dogfooding value.

**No ultraplan amendment is required.** If desired, add one clarifying line under ultraplan §6 Phase I noting the CRM-first narrowing and the single-tenant-mode prerequisite.

---

## 7. Bottom line — "what it would take"

| Goal | What it takes | Effort |
|---|---|---|
| **Single-tenant behavior** | Approach A: a `kmosf.tenancy.mode` flag + one conditional pin-filter + startup tenant provisioner (reusing `TenantBootstrapService`) + tolerate missing `tid`. Reversible; ~3–6 files. | **S** |
| **Easy new-tenant spin-up** | Ultraplan Phase I, scoped CRM-first: image registry + per-tenant Compose/Caddy templates + config renderer + `bootstrap.sh` + DNS/TLS + Restic + thin intake surface, on **Coolify/Hetzner** (not EKS). In-app seam already exists. | **L** (deferred per Phase I timing) |
| **Full code strip (Approach B)** | Not recommended — XL, ~250+ files, irreversible, deletes the test safety net, breaks Pattern A→C. | **XL — avoid** |

**The EKS instinct should be dropped** for ≤8–12 tenants: ~$73/mo always-on control plane + solo Kubernetes ops + AGPL/Pattern-B drift, versus a $30 VPS that is Pattern-A by construction and generalizes cleanly to the full stack. The declarative/GUI workflow you actually want is delivered by **Coolify**. **Revisit only if** tenant count credibly approaches >12 *and* a second hire lands who owns the cluster.

---

## 8. Next actions (non-code)

1. **Persisted:** this repo copy + the canonical Notion page (<https://www.notion.so/362eaa56f92e812091fad24d8e3dda3d>, under "Build FOSS back office solution for KMO clients"). Note: `.claude/` is gitignored in `kmo-digipres-be`, so `docs/design/` is the tracked ADR home — this corrects the memo's original `.claude/` suggestion.
2. **Optional:** add one clarifying line under ultraplan §6 Phase I (CRM-first narrowing + single-tenant-mode prerequisite); no D2/§2 change.
3. **Optional:** spawn a separate, small implementation plan for **Approach A only** (`kmosf.tenancy.mode` single-tenant deployment mode) — standalone dogfooding value, reversible S-effort, does **not** wait on Phase I funding.
4. **Leave Phase I orchestration deferred** per its locked timing (first paying client funds it); when it starts, scope it CRM-first per §5.

### Verification (how to know the recommendations are sound)
- **Approach A claim ("S, reversible"):** confirmed by source — stamping/read-criteria are no-op off the `TenantScoped` path and purely Reactor-context driven (`TenantStampingCallback.java`, `TenantScopedSimpleReactiveMongoRepository.java:37,55,70,85`), so a fixed `TenantContext` requires no model/repo/query/index edits; the seam is `TenantContextHolder.write` (`TenantContextHolder.java:31`).
- **Orchestration claim ("env-ready, seam exists"):** confirmed — every per-tenant value is `${ENV:default}` in `application.properties`; `TenantBootstrapController` `POST /api/tenants` is the final provisioning step; `compose.yaml` is the dev-only baseline the per-tenant template extends.
- **Strategic claims:** cross-checked against `ultraplan-research-back-office-iridescent-wilkes.md` §2, §6 Phase I, §8 D2 and the `project_crm_buildout_plan.md` memory ("multi-tenancy: shared collection + leading-indexed tenantId; never ThreadLocal").

### Critical files referenced
- `src/main/java/com/kumouri/kmodigipresbe/tenancy/TenantWebFilter.java` · `TenantContextHolder.java` · `TenantContext.java` · `TenantStampingCallback.java` · `TenantScopedSimpleReactiveMongoRepository.java` · `JwtTenantResolver.java`
- `src/main/java/com/kumouri/kmodigipresbe/config/MongoConfig.java`
- `src/main/java/com/kumouri/kmodigipresbe/controller/TenantBootstrapController.java` · `service/TenantBootstrapService.java`
- `src/main/resources/application.properties` · `compose.yaml` · `build.gradle`
- Strategy: `C:\Users\willa\.claude\plans\ultraplan-research-back-office-iridescent-wilkes.md` (§2, §6 Phase I, §8 D2)
