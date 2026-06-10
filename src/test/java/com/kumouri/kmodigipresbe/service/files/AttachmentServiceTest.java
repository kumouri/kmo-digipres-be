package com.kumouri.kmodigipresbe.service.files;

import com.kumouri.kmodigipresbe.config.FileStorageProperties;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.repository.AttachmentRepository;
import com.kumouri.kmodigipresbe.service.storage.FileStorageService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Security fix BE-10 — {@link AttachmentService#presignUpload} content-type allowlist + key
 * segment sanitization. Pure unit (storage + repo mocked; no Docker).
 */
class AttachmentServiceTest {

    private FileStorageService storage;
    private AttachmentService service;

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SUBJECT = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        storage = mock(FileStorageService.class);
        AttachmentRepository repo = mock(AttachmentRepository.class);
        FileStorageProperties props = mock(FileStorageProperties.class);
        when(props.uploadTtlSeconds()).thenReturn(600L);
        when(storage.presignUpload(any(), any(), any(), any(), any()))
                .thenReturn(new FileStorageService.Presigned("https://s3/url", "tenants/x/k", "PUT"));
        service = new AttachmentService(repo, storage, props);
    }

    private org.reactivestreams.Publisher<FileStorageService.Presigned> call(
            String subjectType, String contentType, String suffix) {
        return service.presignUpload(subjectType, SUBJECT, contentType, suffix)
                .contextWrite(TenantContextHolder.write(
                        new TenantContext(TENANT, UUID.randomUUID(), Set.of("STAFF"))));
    }

    // ── content-type allowlist ────────────────────────────────────────────────────

    @Test
    void rejectsHtmlContentType() {
        StepVerifier.create(call("INVOICE", "text/html", "pdf"))
                .expectErrorSatisfies(e -> assertThat(((DigiPresBeException) e).getErrorCode())
                        .isEqualTo(4701))
                .verify();
        verify(storage, never()).presignUpload(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsSvgContentType() {
        StepVerifier.create(call("INVOICE", "image/svg+xml", "svg"))
                .expectError(DigiPresBeException.class)
                .verify();
    }

    @Test
    void allowsImageAndPdf() {
        StepVerifier.create(call("INVOICE", "image/png", "png"))
                .expectNextCount(1).verifyComplete();
        StepVerifier.create(call("RECEIPT", "application/pdf", "pdf"))
                .expectNextCount(1).verifyComplete();
        // content type is normalized to lower-case before presigning
        verify(storage).presignUpload(eq(TENANT), any(), eq("image/png"), eq("png"), any());
    }

    // ── key segment sanitization ──────────────────────────────────────────────────

    @Test
    void rejectsTraversalInSubjectType() {
        StepVerifier.create(call("../../etc", "image/png", "png"))
                .expectErrorSatisfies(e -> assertThat(((DigiPresBeException) e).getErrorCode())
                        .isEqualTo(4701))
                .verify();
        verify(storage, never()).presignUpload(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsSlashAndBackslashInSegments() {
        StepVerifier.create(call("a/b", "image/png", "png"))
                .expectError(DigiPresBeException.class).verify();
        StepVerifier.create(call("INVOICE", "image/png", "a\\b"))
                .expectError(DigiPresBeException.class).verify();
    }

    @Test
    void rejectsBlankSubjectType() {
        StepVerifier.create(call("  ", "image/png", "png"))
                .expectError(DigiPresBeException.class).verify();
    }

    @Test
    void allowsNullSuffix() {
        // suffix is optional — null must pass.
        StepVerifier.create(call("INVOICE", "image/png", null))
                .expectNextCount(1).verifyComplete();
    }

    @Test
    void allowsSafeSegmentChars() {
        StepVerifier.create(call("WORK_ORDER-1", "image/webp", "web_p-1"))
                .expectNextCount(1).verifyComplete();
    }
}
