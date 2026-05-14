package com.kumouri.kmodigipresbe.extension;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({TestcontainersConfiguration.class, ModuleRegistryIT.SyntheticModule.class})
class ModuleRegistryIT {

    @Autowired TenantModuleRegistry registry;
    @Autowired TenantRepository tenants;

    @TestConfiguration
    static class SyntheticModule {
        @Bean
        ModuleDefinition syntheticModule() {
            return new ModuleDefinition("synthetic", "Synthetic Test Module", "0.0.0",
                    List.of("WIDGET"));
        }
    }

    private UUID tenantId;

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId)
                .slug("mod-" + tenantId)
                .displayName("Module Test")
                .status(Tenant.TenantStatus.ACTIVE)
                .build()).block();
    }

    @Test
    void registryListsLoadedModules() {
        assertThat(registry.isLoaded("synthetic")).isTrue();
        assertThat(registry.isLoaded("nonexistent")).isFalse();
    }

    @Test
    void requireEnabled_unloadedModule_404() {
        TenantContext ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("ADMIN"));
        StepVerifier.create(registry.requireEnabled("nonexistent")
                        .contextWrite(TenantContextHolder.write(ctx)))
                .expectError()
                .verify();
    }

    @Test
    void requireEnabled_loadedButNotEnabledForTenant_404() {
        TenantContext ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("ADMIN"));
        StepVerifier.create(registry.requireEnabled("synthetic")
                        .contextWrite(TenantContextHolder.write(ctx)))
                .expectError()
                .verify();
    }

    @Test
    void requireEnabled_loadedAndEnabled_completes() {
        Tenant t = tenants.findById(tenantId).block();
        assertThat(t).isNotNull();
        t.setEnabledModules(Set.of("synthetic"));
        tenants.save(t).block();

        TenantContext ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("ADMIN"));
        StepVerifier.create(registry.requireEnabled("synthetic")
                        .contextWrite(TenantContextHolder.write(ctx)))
                .verifyComplete();
    }
}
