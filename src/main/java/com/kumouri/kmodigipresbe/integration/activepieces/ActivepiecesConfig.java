package com.kumouri.kmodigipresbe.integration.activepieces;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link ActivepiecesProperties} (Phase H — H.5).
 *
 * <p>The codebase has no {@code @ConfigurationPropertiesScan}, so the binding
 * is registered explicitly here — the {@code StripeConfig} / {@code CalComConfig}
 * / {@code ImapConfig} precedent.  The {@link ActivepiecesController} bean is
 * separately guarded by
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.activepieces",
 * name="enabled", matchIfMissing=true)}, so this config class is always present
 * (safe to load even with the controller disabled) and carries no conditional
 * of its own.
 */
@Configuration
@EnableConfigurationProperties(ActivepiecesProperties.class)
public class ActivepiecesConfig {
}
