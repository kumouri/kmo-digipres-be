package com.kumouri.kmodigipresbe.service.storage;

import com.kumouri.kmodigipresbe.config.FileStorageProperties;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.Closeable;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

/**
 * S3 SDK v2 presigner-backed impl. Same code path for AWS S3, Cloudflare R2,
 * MinIO — set {@code kmosf.files.endpoint} for the non-AWS targets.
 *
 * <p>Presigning is a synchronous hash + signature (no network IO), safe to call
 * directly from a reactive handler.
 *
 * <h2>Server-side byte store (Phase F — F-D8)</h2>
 * {@link #putBytes} uses an {@link S3AsyncClient} built alongside the existing
 * {@link S3Presigner} in {@link #create} with identical region/endpoint/credentials
 * config so AWS S3, Cloudflare R2, and MinIO all keep working. {@code S3AsyncClient}
 * is in the already-present {@code software.amazon.awssdk:s3} artifact — no new
 * dependency. The call is non-blocking (the SDK returns a
 * {@link java.util.concurrent.CompletableFuture} backed by the SDK's
 * non-blocking Netty HTTP client); wrapping in {@code Mono.fromFuture} keeps
 * everything on the reactive event loop without a {@code boundedElastic} hop.
 */
public class S3FileStorageService implements FileStorageService, Closeable {

    private final S3Presigner presigner;
    private final S3AsyncClient asyncClient;
    private final FileStorageProperties props;

    S3FileStorageService(S3Presigner presigner, S3AsyncClient asyncClient,
                         FileStorageProperties props) {
        this.presigner = presigner;
        this.asyncClient = asyncClient;
        this.props = props;
    }

    public static S3FileStorageService create(FileStorageProperties props) {
        // Shared credential + region/endpoint config for both the presigner and the
        // async client so all three S3-compatible targets (AWS, R2, MinIO) work.
        Region region = Region.of(props.region());
        StaticCredentialsProvider creds = null;
        if (props.accessKey() != null && !props.accessKey().isBlank()) {
            creds = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
        }
        URI endpointOverride = (props.endpoint() != null && !props.endpoint().isBlank())
                ? URI.create(props.endpoint()) : null;

        S3Presigner.Builder presignerBuilder = S3Presigner.builder().region(region);
        if (endpointOverride != null) presignerBuilder.endpointOverride(endpointOverride);
        if (creds != null) presignerBuilder.credentialsProvider(creds);

        S3AsyncClientBuilder asyncBuilder = S3AsyncClient.builder().region(region);
        if (endpointOverride != null) asyncBuilder.endpointOverride(endpointOverride);
        if (creds != null) asyncBuilder.credentialsProvider(creds);

        return new S3FileStorageService(presignerBuilder.build(), asyncBuilder.build(), props);
    }

    @Override
    public Presigned presignUpload(UUID tenantId, String partition, String contentType,
                                   String suffix, Duration ttl) {
        requireBucket();
        String key = buildKey(tenantId, partition, suffix);
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(props.bucket())
                .key(key)
                .contentType(contentType)
                .build();
        PutObjectPresignRequest req = PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(put)
                .build();
        String url = presigner.presignPutObject(req).url().toString();
        return new Presigned(url, key, "PUT");
    }

    @Override
    public String presignDownload(UUID tenantId, String storageRef, Duration ttl) {
        requireBucket();
        String prefix = "tenants/" + tenantId + "/";
        if (!storageRef.startsWith(prefix)) {
            throw new DigiPresBeException(
                    "Refusing to presign a download for a foreign-tenant key", 1311, 403);
        }
        // Security fix BE-10: force the response to download (never render inline) and a
        // benign content type, so a stored object whose content-type slipped past the upload
        // allowlist (or an old object) cannot drive a stored-XSS when the presigned URL is
        // navigated to / embedded. S3 honours these response-* overrides on the presigned GET.
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(props.bucket())
                .key(storageRef)
                .responseContentDisposition("attachment")
                .responseContentType("application/octet-stream")
                .build();
        GetObjectPresignRequest req = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(get)
                .build();
        return presigner.presignGetObject(req).url().toString();
    }

    /**
     * Server-side byte store (Phase F — F-D8). Stores {@code bytes} directly in
     * S3-compatible storage using the non-blocking {@link S3AsyncClient}.
     * Returns the storage ref (key) on success.
     *
     * <p>Legal-integrity note: no transformation is applied to {@code bytes} —
     * the signed PDF is stored exactly as received from Documenso.
     */
    @Override
    public Mono<String> putBytes(UUID tenantId, String partition, byte[] bytes,
                                 String contentType, String suffix) {
        requireBucket();
        String key = buildKey(tenantId, partition, suffix);
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(props.bucket())
                .key(key)
                .contentType(contentType)
                .contentLength((long) bytes.length)
                .build();
        return Mono.fromFuture(() ->
                asyncClient.putObject(put, AsyncRequestBody.fromBytes(bytes))
        ).thenReturn(key);
    }

    /**
     * Server-side byte read (RE-4 — Marketing Studio). The read-twin of
     * {@link #putBytes}: fetches the object at {@code storageRef} into memory via
     * the non-blocking {@link S3AsyncClient} ({@code AsyncResponseTransformer.toBytes()})
     * and returns its bytes. Refuses a foreign-tenant key (the same
     * {@link #presignDownload} {@code tenants/<tenantId>/} prefix guard, {@code 1311}/403).
     */
    @Override
    public Mono<byte[]> getBytes(UUID tenantId, String storageRef) {
        requireBucket();
        String prefix = "tenants/" + tenantId + "/";
        if (storageRef == null || !storageRef.startsWith(prefix)) {
            return Mono.error(new DigiPresBeException(
                    "Refusing to read a foreign-tenant key", 1311, 403));
        }
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(props.bucket())
                .key(storageRef)
                .build();
        return Mono.fromFuture(() ->
                asyncClient.getObject(get, AsyncResponseTransformer.toBytes())
        ).map(software.amazon.awssdk.core.ResponseBytes::asByteArray);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String buildKey(UUID tenantId, String partition, String suffix) {
        return String.format("tenants/%s/%s/%s%s",
                tenantId,
                partition == null ? "misc" : partition,
                UUID.randomUUID(),
                suffix == null || suffix.isBlank() ? "" : "." + suffix);
    }

    private void requireBucket() {
        if (props.bucket() == null || props.bucket().isBlank()) {
            throw new DigiPresBeException(
                    "kmosf.files.bucket is not configured", 1310, 503);
        }
    }

    /**
     * Releases the SDK clients (Phase F — F.11). The {@link S3AsyncClient} added in
     * F-D8 owns a Netty event-loop group + connection pool; without an explicit
     * close it leaks threads/FDs/direct-memory for the life of the JVM. Spring's
     * {@code @Bean} (FileStorageConfig) default destroy-method inference invokes
     * this on context close, so each cached {@code @SpringBootTest} context's
     * client is released on eviction (bounding an otherwise unbounded accumulation
     * that starved the shared Testcontainers Mongo and cascaded the CI suite). Both
     * clients are {@code SdkAutoCloseable}; close is idempotent and null-safe.
     */
    @Override
    public void close() {
        if (asyncClient != null) {
            asyncClient.close();
        }
        if (presigner != null) {
            presigner.close();
        }
    }
}
