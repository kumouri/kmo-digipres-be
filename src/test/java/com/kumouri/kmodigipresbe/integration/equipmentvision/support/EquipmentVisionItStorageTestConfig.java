package com.kumouri.kmodigipresbe.integration.equipmentvision.support;

import com.kumouri.kmodigipresbe.service.storage.FileStorageService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * In-memory {@link FileStorageService} stub for {@code EquipmentVisionIT} — exercises the server-side
 * {@code putBytes} photo-store path without a real object store (HS-2). A faithful clone of the
 * Phase-3 {@code MoleTripwireItStorageTestConfig} / Phase-2 {@code MoleTriageItStorageTestConfig}:
 * declaring the stub as a real {@code @Bean @Primary} (NOT {@code @MockBean}) keeps it off the
 * functional-shard-splinter list — it's the precedented Attachment/file-IT storage stub, not a
 * behaviour mock.
 *
 * <p>{@link #putBytes} returns {@code "tenants/<tenantId>/<partition>/<uuid>.<suffix>"} — the exact
 * tenant-prefixed ref shape the real {@code S3FileStorageService} produces. The presign methods
 * return synthetic values (the equipment-photo path never presigns).
 */
@TestConfiguration(proxyBeanMethods = false)
public class EquipmentVisionItStorageTestConfig {

    @Bean
    @Primary
    FileStorageService equipmentVisionItFileStorageService() {
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

            @Override
            public Mono<byte[]> getBytes(UUID tenantId, String storageRef) {
                return Mono.just(new byte[0]);
            }
        };
    }
}
