package com.kumouri.kmodigipresbe.controller.admin;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/admin/modules")
@RequiredArgsConstructor
public class ModuleAdminController {

    private final TenantModuleRegistry registry;
    private final TenantRepository tenants;

    public record ModuleStatus(String key, String displayName, String version,
                               List<String> providedEntities, boolean enabledForCurrentTenant) {
    }

    @GetMapping
    public Flux<ModuleStatus> list() {
        return TenantContextHolder.required()
                .flatMap(ctx -> tenants.findById(ctx.tenantId())
                        .switchIfEmpty(Mono.error(new DigiPresBeException(
                                "Tenant not found", 1131, 404))))
                .flatMapMany(tenant -> Flux.fromIterable(registry.all())
                        .map(def -> new ModuleStatus(
                                def.key(),
                                def.displayName(),
                                def.version(),
                                def.providedEntities(),
                                TenantModuleRegistry.isEnabledForTenant(def.key(), tenant.getEnabledModules()))));
    }

    @PostMapping("/{key}:enable")
    @ResponseStatus(HttpStatus.OK)
    public Mono<Tenant> enable(@PathVariable String key) {
        return mutate(key, true);
    }

    @PostMapping("/{key}:disable")
    @ResponseStatus(HttpStatus.OK)
    public Mono<Tenant> disable(@PathVariable String key) {
        return mutate(key, false);
    }

    private Mono<Tenant> mutate(String key, boolean enable) {
        if (!registry.isLoaded(key)) {
            return Mono.error(new DigiPresBeException(
                    "Module '" + key + "' is not loaded on this server", 1130, 404));
        }
        return TenantContextHolder.required()
                .flatMap(ctx -> tenants.findById(ctx.tenantId())
                        .switchIfEmpty(Mono.error(new DigiPresBeException(
                                "Tenant not found", 1131, 404)))
                        .flatMap(tenant -> {
                            Set<String> updated = new HashSet<>(
                                    tenant.getEnabledModules() == null
                                            ? Set.of()
                                            : tenant.getEnabledModules());
                            if (enable) updated.add(key); else updated.remove(key);
                            tenant.setEnabledModules(updated);
                            return tenants.save(tenant);
                        }));
    }
}
