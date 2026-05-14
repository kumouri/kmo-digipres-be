package com.kumouri.kmodigipresbe.module.fieldservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kmosf.files")
public record FieldServiceProperties(
        String region,
        String bucket,
        String endpoint,
        String accessKey,
        String secretKey,
        long uploadTtlSeconds
) {
}
