/**
 * Multi-tenancy primitives.
 *
 * <h2>How it works</h2>
 * <ul>
 *   <li>{@link com.kumouri.kmodigipresbe.tenancy.TenantContext} is the per-request tenant
 *       identity. It lives in the Reactor {@link reactor.util.context.Context}, never in a
 *       {@code ThreadLocal} — that is the only safe place for it under WebFlux because
 *       operators may switch threads.</li>
 *   <li>{@link com.kumouri.kmodigipresbe.tenancy.TenantWebFilter} runs early in the WebFlux
 *       filter chain, reads the authenticated JWT (or any other resolver in the future),
 *       and writes a {@code TenantContext} into the Reactor Context for the remainder of
 *       the pipeline.</li>
 *   <li>{@link com.kumouri.kmodigipresbe.tenancy.TenantStampingCallback} is a
 *       {@code ReactiveBeforeConvertCallback} that auto-stamps {@code tenantId} on every
 *       save of an entity implementing {@link com.kumouri.kmodigipresbe.tenancy.TenantScoped}.
 *       Use the reactive variant — the synchronous {@code BeforeConvertCallback} cannot
 *       see the Reactor Context.</li>
 *   <li>{@link com.kumouri.kmodigipresbe.tenancy.TenantScopedSimpleReactiveMongoRepository}
 *       is the custom Spring Data base that auto-applies a tenantId predicate on
 *       {@code findById}, {@code findAll}, {@code existsById}, {@code count}, and the
 *       delete operations. Custom finder methods on individual repositories are
 *       <strong>not</strong> auto-filtered — by convention they must include {@code tenantId}
 *       in their derivation (e.g. {@code findByTenantIdAndEmail}) and the service layer
 *       must pass it from {@link com.kumouri.kmodigipresbe.tenancy.TenantContextHolder}.</li>
 * </ul>
 *
 * <h2>Escape hatch to DB-per-tenant</h2>
 * Swap {@link com.kumouri.kmodigipresbe.tenancy.JwtTenantResolver} for a resolver that
 * also selects a {@code ReactiveMongoDatabaseFactory} per tenant. The data model and
 * repositories don't need to change.
 */
package com.kumouri.kmodigipresbe.tenancy;
