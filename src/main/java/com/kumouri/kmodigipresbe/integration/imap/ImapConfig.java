package com.kumouri.kmodigipresbe.integration.imap;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link ImapProperties} (Phase H — H.3).
 *
 * <p>The codebase has no {@code @ConfigurationPropertiesScan}, so the binding
 * is registered explicitly here — the {@code StripeConfig} / {@code CalComConfig}
 * precedent. The {@code ImapInboundPoller} bean is separately guarded by
 * {@code @ConditionalOnProperty(prefix="kmosf.imap.inbound", name="enabled",
 * matchIfMissing=false)}, so this config class is always present (safe to load
 * even with the poller disabled) and carries no conditional of its own.
 */
@Configuration
@EnableConfigurationProperties(ImapProperties.class)
public class ImapConfig {
}
