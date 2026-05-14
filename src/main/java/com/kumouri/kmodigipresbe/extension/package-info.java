/**
 * Per-tenant extensibility primitives.
 *
 * <h2>Two layers of extension</h2>
 *
 * <h3>Custom fields on existing entities</h3>
 * Tenants register {@link com.kumouri.kmodigipresbe.extension.FieldDefinition} records
 * describing typed fields (text/number/date/bool/enum/lookup) on
 * {@link com.kumouri.kmodigipresbe.extension.CustomFieldHost} entities (Contact,
 * Company, Deal, Activity in Phase 2; WorkOrder + JobSite added in Phase 3).
 *
 * <p>Values live under {@code customFields[key]} on the host entity. The
 * {@link com.kumouri.kmodigipresbe.extension.CustomFieldValidator} runs as a
 * {@link org.springframework.data.mongodb.core.mapping.event.ReactiveBeforeSaveCallback}
 * on every save, fetches the per-tenant definitions, and rejects missing-required,
 * wrong-type, and out-of-enum-options values.
 *
 * <h3>Custom modules (whole new entity types)</h3>
 * A vertical module is a self-contained package shipping an {@code @AutoConfiguration}
 * class gated on a {@code kmosf.modules.<key>.enabled} property. The auto-config
 * exposes a {@link com.kumouri.kmodigipresbe.extension.ModuleDefinition} bean which the
 * {@link com.kumouri.kmodigipresbe.extension.TenantModuleRegistry} picks up at startup.
 *
 * <p>Per-tenant enablement is layered on top:
 * {@link com.kumouri.kmodigipresbe.model.tenant.Tenant#getEnabledModules()} is the
 * authoritative per-tenant set, mutated through
 * {@code POST /admin/modules/{key}:enable|:disable}. Vertical-module controllers must
 * call {@link com.kumouri.kmodigipresbe.extension.TenantModuleRegistry#requireEnabled}
 * at the entry of each handler — without it, a tenant without the module gets a 401
 * (auth-required) instead of a clean 404.
 */
package com.kumouri.kmodigipresbe.extension;
