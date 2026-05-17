package com.kumouri.kmodigipresbe.contract.support;

import com.kumouri.kmodigipresbe.service.storage.FileStorageService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Shared in-memory {@link FileStorageService} stub for contract IT classes that exercise
 * the server-side {@code putBytes} path (the signed/rendered-PDF S3 write) without a
 * real object store.
 *
 * <p>Used by {@code DocumensoSendWireMockIT} and {@code DocumensoWebhookSignedIT} via
 * {@code @Import({TestcontainersConfiguration.class, ContractItStorageTestConfig.class})}.
 * Declaring the stub here (rather than via {@code @MockBean}) means both ITs share ONE
 * ApplicationContext cache key — the #58-proven approach to avoid Testcontainers Mongo
 * saturation from extra context boots (F.10 fix).
 *
 * <h2>Stub behaviour</h2>
 * <ul>
 *   <li>{@link #putBytes} returns
 *       {@code "tenants/<tenantId>/<partition>/<uuid>.<suffix>"} — the exact ref-shape
 *       the previous {@code @MockBean thenAnswer} produced, so all
 *       {@code signedPdfStorageRef.startsWith("tenants/<tenantId>/")} assertions still
 *       hold.</li>
 *   <li>{@link #presignUpload} and {@link #presignDownload} return synthetic
 *       values — contract ITs do not exercise those paths.</li>
 * </ul>
 */
@TestConfiguration(proxyBeanMethods = false)
public class ContractItStorageTestConfig {

    @Bean
    @Primary
    FileStorageService contractItFileStorageService() {
        return new FileStorageService() {

            @Override
            public Presigned presignUpload(UUID tenantId, String partition,
                                           String contentType, String suffix, Duration ttl) {
                String ref = "tenants/" + tenantId + "/" + partition + "/" + UUID.randomUUID() + "." + suffix;
                return new Presigned("https://s3.test/presign-put/" + ref, ref, "PUT");
            }

            @Override
            public String presignDownload(UUID tenantId, String storageRef, Duration ttl) {
                return "https://s3.test/presign-get/" + storageRef;
            }

            @Override
            public Mono<String> putBytes(UUID tenantId, String partition, byte[] bytes,
                                         String contentType, String suffix) {
                return Mono.just("tenants/" + tenantId + "/" + partition + "/"
                        + UUID.randomUUID() + "." + suffix);
            }
        };
    }
}
