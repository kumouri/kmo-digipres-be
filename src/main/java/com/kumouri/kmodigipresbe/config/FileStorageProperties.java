package com.kumouri.kmodigipresbe.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3-compatible storage config. Bound to {@code kmosf.files.*}; works against
 * AWS S3, Cloudflare R2, or MinIO via {@link #endpoint()} override.
 */
@ConfigurationProperties(prefix = "kmosf.files")
public record FileStorageProperties(
        String region,
        String bucket,
        String endpoint,
        String accessKey,
        String secretKey,
        long uploadTtlSeconds
) {
}
