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
import java.util.Set;
import java.util.regex.Pattern;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AttachmentService {

    /**
     * Security fix BE-10 — allowlisted presign content types. Deliberately excludes
     * {@code image/svg+xml} and {@code text/html} (active-content types that drive the
     * stored-XSS path when a presigned GET serves them inline). Only inert image formats
     * and PDF may be stored via the presign path.
     */
    static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif", "application/pdf");

    /**
     * Security fix BE-10 — {@code subjectType} / {@code suffix} are concatenated into the
     * S3 object key. Restrict them to a safe character class (mirrors the constrained suffix
     * {@code MoleTriageService.suffixFor} produces); reject {@code /}, {@code \}, {@code ..},
     * and anything outside {@code [A-Za-z0-9_-]} to keep the key inside the
     * {@code tenants/<id>/} prefix and free of control/traversal characters.
     */
    private static final Pattern SAFE_KEY_SEGMENT = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

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
        // Security fix BE-10: allowlist the content type + sanitize the key segments before
        // presigning (all inside the reactive chain so failures are Mono.errors). A
        // disallowed/active content type or a traversal/illegal char is a 400, not a
        // presigned URL for an attacker-controlled object.
        return Mono.defer(() -> {
            if (contentType == null
                    || !ALLOWED_CONTENT_TYPES.contains(contentType.trim().toLowerCase())) {
                return Mono.<FileStorageService.Presigned>error(new DigiPresBeException(
                        "Unsupported attachment content type (allowed: " + ALLOWED_CONTENT_TYPES + ")",
                        4701, 400));
            }
            DigiPresBeException segmentError = checkSegment("subjectType", subjectType, true);
            if (segmentError == null) {
                segmentError = checkSegment("suffix", suffix, false); // suffix optional
            }
            if (segmentError != null) {
                return Mono.error(segmentError);
            }
            return TenantContextHolder.required().map(ctx -> storage.presignUpload(
                    ctx.tenantId(),
                    "attachments/" + subjectType + "/" + subjectId,
                    contentType.trim().toLowerCase(),
                    suffix,
                    Duration.ofSeconds(props.uploadTtlSeconds())));
        });
    }

    /**
     * Security fix BE-10: returns a {@link DigiPresBeException} (4701/400) when a key segment
     * contains {@code /}, {@code \}, {@code ..}, or any non-{@code [A-Za-z0-9_-]} char, else
     * null. {@code required=false} permits a null/blank value (the optional {@code suffix}); a
     * present value is always validated.
     */
    private static DigiPresBeException checkSegment(String field, String value, boolean required) {
        if (value == null || value.isBlank()) {
            return required ? new DigiPresBeException(field + " is required", 4701, 400) : null;
        }
        if (!SAFE_KEY_SEGMENT.matcher(value).matches()) {
            return new DigiPresBeException(
                    field + " contains illegal characters (allowed: A-Z a-z 0-9 _ -)", 4701, 400);
        }
        return null;
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
