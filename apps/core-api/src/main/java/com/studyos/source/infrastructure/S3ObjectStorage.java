package com.studyos.source.infrastructure;

import com.studyos.shared.web.ApiException;
import com.studyos.source.application.port.ObjectStorage;
import java.net.URI;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.*;
import software.amazon.awssdk.services.s3.presigner.model.*;

@Component
public class S3ObjectStorage implements ObjectStorage {
    private final S3Client s3;
    private final S3Presigner signer;
    private final String bucket;

    public S3ObjectStorage(
            @Value("${studyos.storage.endpoint}") String endpoint,
            @Value("${studyos.storage.public-endpoint}") String publicEndpoint,
            @Value("${studyos.storage.bucket}") String bucket,
            @Value("${studyos.storage.region}") String region,
            @Value("${studyos.storage.access-key}") String access,
            @Value("${studyos.storage.secret-key}") String secret) {
        var credentials =
                StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret));
        var config = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        s3 =
                S3Client.builder()
                        .endpointOverride(URI.create(endpoint))
                        .region(Region.of(region))
                        .credentialsProvider(credentials)
                        .serviceConfiguration(config)
                        .build();
        signer =
                S3Presigner.builder()
                        .endpointOverride(URI.create(publicEndpoint))
                        .region(Region.of(region))
                        .credentialsProvider(credentials)
                        .serviceConfiguration(config)
                        .build();
        this.bucket = bucket;
    }

    public Upload upload(String key, String mime) {
        var request = PutObjectRequest.builder().bucket(bucket).key(key).contentType(mime).build();
        var url =
                signer.presignPutObject(
                        PutObjectPresignRequest.builder()
                                .signatureDuration(Duration.ofMinutes(10))
                                .putObjectRequest(request)
                                .build());
        return new Upload(
                url.url().toString(), Instant.now().plusSeconds(600), Map.of("Content-Type", mime));
    }

    public void put(String key, byte[] bytes, String mime) {
        s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).contentType(mime).build(),
                RequestBody.fromBytes(bytes));
    }

    public Download download(String key, String mime) {
        var request =
                GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .responseContentType(mime)
                        .responseContentDisposition(
                                "application/pdf".equals(mime) ? "inline" : "attachment")
                        .build();
        var presigned =
                signer.presignGetObject(
                        GetObjectPresignRequest.builder()
                                .signatureDuration(Duration.ofMinutes(5))
                                .getObjectRequest(request)
                                .build());
        return new Download(presigned.url().toString(), Instant.now().plusSeconds(300));
    }

    public void seal(String stagingKey, String sealedKey, long size, String checksum, String mime) {
        if (!stagingKey.startsWith("staging/") || sealedKey.startsWith("staging/"))
            throw new IllegalArgumentException("Uploads must be promoted out of staging");
        try {
            // Copy first: the signed upload URL can still mutate staging, never the private
            // snapshot.
            s3.copyObject(
                    CopyObjectRequest.builder()
                            .sourceBucket(bucket)
                            .sourceKey(stagingKey)
                            .destinationBucket(bucket)
                            .destinationKey(sealedKey)
                            .build());
            ObjectInfo actual = inspect(sealedKey, size);
            if (actual.size() != size
                    || !checksum.equals(actual.checksum())
                    || !mime.equals(actual.mimeType()))
                throw ApiException.badRequest(
                        "UPLOAD_MISMATCH",
                        "Uploaded content does not match its size, checksum or MIME type.");
        } catch (RuntimeException e) {
            try {
                s3.deleteObject(
                        DeleteObjectRequest.builder().bucket(bucket).key(sealedKey).build());
            } catch (Exception ignored) {
            }
            if (e instanceof ApiException api) throw api;
            if (e instanceof NoSuchKeyException)
                throw ApiException.badRequest(
                        "UPLOAD_NOT_FOUND", "Upload the file before completing the source.");
            throw new ApiException(
                    503, "STORAGE_UNAVAILABLE", "Unable to verify the uploaded file.");
        }
        // Staging is deliberately retained until its one-day lifecycle expiry: an old PUT URL
        // may recreate it, while the sealed source remains immutable and privately addressed.
    }

    public ObjectInfo inspect(String key, long maxBytes) {
        try (var stream =
                s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            if (stream.response().contentLength() > maxBytes)
                throw ApiException.badRequest(
                        "UPLOAD_TOO_LARGE", "Uploaded file exceeds its declared size.");
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            long size = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                size += count;
                if (size > maxBytes)
                    throw ApiException.badRequest(
                            "UPLOAD_TOO_LARGE", "Uploaded file exceeds its declared size.");
                digest.update(buffer, 0, count);
            }
            return new ObjectInfo(
                    size,
                    HexFormat.of().formatHex(digest.digest()),
                    stream.response().contentType());
        } catch (ApiException e) {
            throw e;
        } catch (NoSuchKeyException e) {
            throw ApiException.badRequest(
                    "UPLOAD_NOT_FOUND", "Upload the file before completing the source.");
        } catch (Exception e) {
            throw new ApiException(
                    503, "STORAGE_UNAVAILABLE", "Unable to verify the uploaded file.");
        }
    }
}
