package com.kumouri.kmodigipresbe.module.fieldservice.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.fieldservice.config.FieldServiceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

/**
 * S3-compatible presigner. Works against AWS S3, Cloudflare R2, MinIO etc. — just
 * point {@code kmosf.files.endpoint} at the right URL (leave blank for AWS).
 *
 * <p>Object keys live under {@code tenants/<tenantId>/work-orders/<workOrderId>/<random>.<suffix>}
 * — tenant-prefixed so a misconfigured bucket policy still keeps tenants apart
 * at the object-key level. Presign TTL is configurable via
 * {@code kmosf.files.upload-ttl-seconds}, default 10 minutes.
 *
 * <p>Presigning itself is a synchronous hash + signature operation, not network
 * IO. Safe to call from a reactive handler without {@code subscribeOn}.
 */
@Slf4j
@RequiredArgsConstructor
public class S3FileStorageService implements FileStorageService {

    private final S3Presigner presigner;
    private final FieldServiceProperties props;

    public static S3FileStorageService create(FieldServiceProperties props) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(props.region()));
        if (props.endpoint() != null && !props.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(props.endpoint()));
        }
        if (props.accessKey() != null && !props.accessKey().isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.accessKey(), props.secretKey())));
        }
        return new S3FileStorageService(builder.build(), props);
    }

    @Override
    public Presigned presignUpload(UUID tenantId, UUID workOrderId, String contentType,
                                   String suffix, Duration ttl) {
        if (props.bucket() == null || props.bucket().isBlank()) {
            throw new DigiPresBeException(
                    "kmosf.files.bucket is not configured", 1310, 503);
        }
        String key = String.format("tenants/%s/work-orders/%s/%s%s",
                tenantId, workOrderId, UUID.randomUUID(),
                suffix == null || suffix.isBlank() ? "" : "." + suffix);
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
        if (props.bucket() == null || props.bucket().isBlank()) {
            throw new DigiPresBeException(
                    "kmosf.files.bucket is not configured", 1310, 503);
        }
        String prefix = "tenants/" + tenantId + "/";
        if (!storageRef.startsWith(prefix)) {
            throw new DigiPresBeException(
                    "Refusing to presign a download for a foreign-tenant key", 1311, 403);
        }
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(props.bucket())
                .key(storageRef)
                .build();
        GetObjectPresignRequest req = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(get)
                .build();
        return presigner.presignGetObject(req).url().toString();
    }
}
