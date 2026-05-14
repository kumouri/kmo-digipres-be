package com.kumouri.kmodigipresbe.extension;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registers all globally-loaded {@link ModuleDefinition} beans at startup and answers
 * the two-stage question: <em>is this module loaded AND enabled for the current
 * tenant?</em>
 *
 * <p>Phase 2 reads the tenant's enabled-modules set per request. A small cache is fine
 * to add later but not needed at this scale.
 */
@Slf4j
@Component
public class TenantModuleRegistry {

    private final Map<String, ModuleDefinition> byKey;
    private final TenantRepository tenants;

    public TenantModuleRegistry(List<ModuleDefinition> registered, TenantRepository tenants) {
        Map<String, ModuleDefinition> map = new HashMap<>();
        for (ModuleDefinition def : registered) {
            ModuleDefinition prior = map.put(def.key(), def);
            if (prior != null) {
                throw new IllegalStateException("Duplicate ModuleDefinition for key " + def.key());
            }
        }
        this.byKey = Map.copyOf(map);
        this.tenants = tenants;
        log.info("TenantModuleRegistry: registered modules {}", byKey.keySet());
    }

    public List<ModuleDefinition> all() {
        return List.copyOf(byKey.values());
    }

    public boolean isLoaded(String key) {
        return byKey.containsKey(key);
    }

    public static boolean isEnabledForTenant(String key, Set<String> enabledModules) {
        return enabledModules != null && enabledModules.contains(key);
    }

    /**
     * Resolve "loaded AND enabled for the current tenant in the Reactor Context."
     * Errors with {@link DigiPresBeException} 1130 (404) if not — vertical-module
     * controllers should call this at the entry of every handler.
     */
    public Mono<Void> requireEnabled(String key) {
        if (!isLoaded(key)) {
            return Mono.error(new DigiPresBeException(
                    "Module '" + key + "' is not loaded on this server", 1130, 404));
        }
        return TenantContextHolder.required()
                .flatMap(ctx -> tenants.findById(ctx.tenantId())
                        .switchIfEmpty(Mono.error(new DigiPresBeException(
                                "Tenant not found", 1131, 404)))
                        .flatMap(tenant -> isEnabledForTenant(key, tenant.getEnabledModules())
                                ? Mono.empty()
                                : Mono.error(new DigiPresBeException(
                                        "Module '" + key + "' is not enabled for this tenant",
                                        1132, 404))));
    }
}
