package com.kumouri.kmodigipresbe.module.homeservices;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Server-level module gating for home-services. Tenant-level gating (the
 * {@code TenantModuleRegistry.requireEnabled} check at handler entry) is covered
 * by {@link com.kumouri.kmodigipresbe.extension.ModuleRegistryIT} against a
 * synthetic module and applies identically here.
 */
class HomeServicesModuleGatingIT {

    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    @TestPropertySource(properties = "kmosf.modules.home-services.enabled=false")
    @ExtendWith(SpringExtension.class)
    static class Disabled {
        @Autowired ApplicationContext ctx;

        @Test
        void homeServicesModuleDefinitionAbsent() {
            assertThat(ctx.getBeansOfType(ModuleDefinition.class))
                    .noneSatisfy((name, def) -> assertThat(def.key()).isEqualTo("home-services"));
        }
    }

    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    @TestPropertySource(properties = {
            "kmosf.modules.home-services.enabled=true",
            "kmosf.files.region=us-east-1"
    })
    @ExtendWith(SpringExtension.class)
    static class Enabled {
        @Autowired ApplicationContext ctx;

        @Test
        void homeServicesModuleDefinitionRegistered() {
            assertThat(ctx.getBeansOfType(ModuleDefinition.class))
                    .anySatisfy((name, def) -> {
                        assertThat(def.key()).isEqualTo("home-services");
                        assertThat(def.displayName()).isEqualTo("Home Services");
                        assertThat(def.providedEntities())
                                .containsExactly("EQUIPMENT", "SERVICE_AGREEMENT", "MAINTENANCE_VISIT");
                    });
        }
    }
}
