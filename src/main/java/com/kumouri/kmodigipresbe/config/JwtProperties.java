package com.kumouri.kmodigipresbe.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kmosf.jwt")
public record JwtProperties(String secret, String issuer, long ttlSeconds) {
}
