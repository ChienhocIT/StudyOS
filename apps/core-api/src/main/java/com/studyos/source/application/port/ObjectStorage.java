package com.studyos.source.application.port;

import java.time.Instant;
import java.util.Map;

public interface ObjectStorage {
    record Upload(String url, Instant expiresAt, Map<String, String> headers) {}

    record ObjectInfo(long size, String checksum, String mimeType) {}

    record Download(String url, Instant expiresAt) {}

    Upload upload(String key, String mimeType);

    void put(String key, byte[] bytes, String mimeType);

    ObjectInfo inspect(String key, long maxBytes);

    void seal(String stagingKey, String sealedKey, long size, String checksum, String mimeType);

    Download download(String key, String mimeType);
}
