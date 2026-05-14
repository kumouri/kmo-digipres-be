package com.kumouri.kmodigipresbe.extension;

import java.util.List;

/**
 * Metadata for a vertical module (field-service, future restaurant/e-commerce, etc.).
 * A module contributes one of these as a Spring bean from its {@code @AutoConfiguration};
 * the {@link TenantModuleRegistry} indexes all of them by {@link #key()} on startup.
 *
 * <p>A module is reachable for a request only when:
 * <ol>
 *   <li>Its bean was registered (global property + {@code @ConditionalOnProperty} gate).</li>
 *   <li>The current tenant has the key in {@code Tenant.enabledModules} — see
 *       {@link TenantModuleRegistry#isEnabledForTenant(String, java.util.Set)}.</li>
 * </ol>
 */
public record ModuleDefinition(
        String key,
        String displayName,
        String version,
        List<String> providedEntities) {
}
