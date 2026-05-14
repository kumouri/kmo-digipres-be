package com.kumouri.kmodigipresbe.service.storage;

import com.kumouri.kmodigipresbe.config.FileStorageProperties;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import lombok.RequiredArgsConstructor;
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
 * S3 SDK v2 presigner-backed impl. Same code path for AWS S3, Cloudflare R2,
 * MinIO — set {@code kmosf.files.endpoint} for the non-AWS targets.
 *
 * <p>Presigning is a synchronous hash + signature (no network IO), safe to call
 * directly from a reactive handler.
 */
@RequiredArgsConstructor
public class S3FileStorageService implements FileStorageService {

    private final S3Presigner presigner;
    private final FileStorageProperties props;

    public static S3FileStorageService create(FileStorageProperties props) {
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
    public Presigned presignUpload(UUID tenantId, String partition, String contentType,
                                   String suffix, Duration ttl) {
        requireBucket();
        String key = String.format("tenants/%s/%s/%s%s",
                tenantId,
                partition == null ? "misc" : partition,
                UUID.randomUUID(),
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
        requireBucket();
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

    private void requireBucket() {
        if (props.bucket() == null || props.bucket().isBlank()) {
            throw new DigiPresBeException(
                    "kmosf.files.bucket is not configured", 1310, 503);
        }
    }
}
