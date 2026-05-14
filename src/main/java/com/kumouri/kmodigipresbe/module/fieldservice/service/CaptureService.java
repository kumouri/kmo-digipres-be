package com.kumouri.kmodigipresbe.module.fieldservice.service;

import com.kumouri.kmodigipresbe.config.FileStorageProperties;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.fieldservice.model.Capture;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.CaptureRepository;
import com.kumouri.kmodigipresbe.service.storage.FileStorageService;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@RequiredArgsConstructor
public class CaptureService {

    private final CaptureRepository captures;
    private final FileStorageService storage;
    private final FileStorageProperties props;

    public Mono<FileStorageService.Presigned> presignUpload(UUID workOrderId,
                                                            String contentType,
                                                            String suffix) {
        return TenantContextHolder.required().map(ctx -> storage.presignUpload(
                ctx.tenantId(),
                "work-orders/" + workOrderId,
                contentType,
                suffix,
                Duration.ofSeconds(props.uploadTtlSeconds())));
    }

    public Mono<String> presignDownload(String storageRef) {
        return TenantContextHolder.required().map(ctx -> storage.presignDownload(
                ctx.tenantId(),
                storageRef,
                Duration.ofSeconds(props.uploadTtlSeconds())));
    }

    public Mono<Capture> register(Capture capture) {
        capture.setId(null);
        if (capture.getCapturedAt() == null) capture.setCapturedAt(Instant.now());
        if (capture.getStorageRef() == null || capture.getStorageRef().isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "storageRef is required", 1340, 400));
        }
        return TenantContextHolder.required().flatMap(ctx -> {
            String prefix = "tenants/" + ctx.tenantId() + "/";
            if (!capture.getStorageRef().startsWith(prefix)) {
                return Mono.error(new DigiPresBeException(
                        "storageRef does not belong to this tenant", 1341, 403));
            }
            if (capture.getCapturedByUserId() == null) {
                capture.setCapturedByUserId(ctx.userId());
            }
            return captures.save(capture);
        });
    }

    public Flux<Capture> listForWorkOrder(UUID workOrderId) {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> captures
                        .findAllByTenantIdAndWorkOrderIdOrderByCapturedAtDesc(ctx.tenantId(), workOrderId));
    }
}
