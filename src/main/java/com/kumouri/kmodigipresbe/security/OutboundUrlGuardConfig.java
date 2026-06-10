package com.kumouri.kmodigipresbe.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link OutboundUrlGuardProperties} (security fix BE-08/09/16). Mirrors the
 * per-integration {@code @EnableConfigurationProperties} config pattern
 * ({@code DocumensoConfig} / {@code GbpConfig}).
 */
@Configuration
@EnableConfigurationProperties(OutboundUrlGuardProperties.class)
public class OutboundUrlGuardConfig {
}
