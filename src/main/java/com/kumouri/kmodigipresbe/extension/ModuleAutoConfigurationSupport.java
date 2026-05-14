package com.kumouri.kmodigipresbe.extension;

import java.util.List;

/**
 * Helper for vertical modules' {@code @AutoConfiguration} classes. Subclasses (or
 * just-call-the-static factory) expose their {@link ModuleDefinition} as a bean that
 * the {@link TenantModuleRegistry} picks up.
 *
 * <p>The minimal vertical-module shape is:
 * <pre>
 * &#64;AutoConfiguration
 * &#64;ConditionalOnProperty(prefix = "kmosf.modules.field-service", name = "enabled")
 * public class FieldServiceAutoConfiguration {
 *     &#64;Bean
 *     public ModuleDefinition fieldServiceModule() {
 *         return ModuleAutoConfigurationSupport.module(
 *             "field-service", "Field Service", "1.0.0",
 *             List.of("WORK_ORDER", "JOB_SITE"));
 *     }
 *     // ... controllers, services, repositories ...
 * }
 * </pre>
 *
 * <p>Plus an entry in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * pointing at the auto-configuration class.
 */
public final class ModuleAutoConfigurationSupport {

    private ModuleAutoConfigurationSupport() {
    }

    public static ModuleDefinition module(String key, String displayName, String version,
                                          List<String> providedEntities) {
        return new ModuleDefinition(key, displayName, version, providedEntities);
    }
}
