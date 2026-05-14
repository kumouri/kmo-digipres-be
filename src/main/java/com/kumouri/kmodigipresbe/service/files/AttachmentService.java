package com.kumouri.kmodigipresbe.service.files;

import com.kumouri.kmodigipresbe.config.FileStorageProperties;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.files.Attachment;
import com.kumouri.kmodigipresbe.repository.AttachmentRepository;
import com.kumouri.kmodigipresbe.service.storage.FileStorageService;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AttachmentService {

    private final AttachmentRepository attachments;
    private final FileStorageService storage;
    private final FileStorageProperties props;

    public Flux<Attachment> listFor(String subjectType, UUID subjectId) {
        return TenantContextHolder.required().flatMapMany(ctx ->
                attachments.findAllByTenantIdAndSubjectTypeAndSubjectIdOrderByCreatedAtDesc(
                        ctx.tenantId(), subjectType, subjectId));
    }

    public Mono<FileStorageService.Presigned> presignUpload(String subjectType, UUID subjectId,
                                                            String contentType, String suffix) {
        return TenantContextHolder.required().map(ctx -> storage.presignUpload(
                ctx.tenantId(),
                "attachments/" + subjectType + "/" + subjectId,
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

    public Mono<Attachment> register(Attachment toCreate) {
        if (toCreate.getStorageRef() == null || toCreate.getStorageRef().isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "storageRef is required", 2400, 400));
        }
        toCreate.setId(null);
        return TenantContextHolder.required().flatMap(ctx -> {
            String prefix = "tenants/" + ctx.tenantId() + "/";
            if (!toCreate.getStorageRef().startsWith(prefix)) {
                return Mono.error(new DigiPresBeException(
                        "storageRef does not belong to this tenant", 2401, 403));
            }
            if (toCreate.getUploadedByUserId() == null) {
                toCreate.setUploadedByUserId(ctx.userId());
            }
            return attachments.save(toCreate);
        });
    }

    public Mono<Void> delete(UUID id) {
        return attachments.deleteById(id);
    }
}
