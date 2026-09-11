package com.studyos.source.application;

import com.studyos.identity.application.AuthService;
import com.studyos.notebook.application.NotebookAccess;
import com.studyos.shared.outbox.Outbox;
import com.studyos.shared.persistence.Rows;
import com.studyos.shared.web.ApiException;
import com.studyos.source.application.port.*;
import com.studyos.source.domain.SourceState;
import com.studyos.studio.application.SourceArtifactInvalidation;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceService implements SourceLookup {
    private final SourceRepository repo;
    private final NotebookAccess notebooks;
    private final ObjectStorage storage;
    private final Outbox outbox;
    private final SourceArtifactInvalidation artifacts;
    private static final Map<String, String> MIME =
            Map.of(
                    "application/pdf",
                    "PDF",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "DOCX",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "PPTX",
                    "text/plain",
                    "RAW_TEXT");

    public SourceService(
            SourceRepository repo,
            NotebookAccess notebooks,
            ObjectStorage storage,
            Outbox outbox,
            SourceArtifactInvalidation artifacts) {
        this.repo = repo;
        this.notebooks = notebooks;
        this.storage = storage;
        this.outbox = outbox;
        this.artifacts = artifacts;
    }

    public List<Map<String, Object>> list(UUID user, UUID notebook) {
        notebooks.requireRead(user, notebook);
        return repo.list(notebook).stream().map(this::visible).toList();
    }

    public Map<String, Object> get(UUID user, UUID id) {
        return visible(authorized(user, id, false));
    }

    public Map<String, Object> download(UUID user, UUID id) {
        var row = authorized(user, id, false);
        String type = row.get("type").toString();
        if (Set.of("WEB", "YOUTUBE").contains(type)
                || row.get("objectKey") == null
                || "UPLOADING".equals(row.get("status")))
            throw ApiException.conflict(
                    "SOURCE_DOWNLOAD_UNAVAILABLE",
                    "An uploaded original file is not available for this source.");
        String mime = Objects.toString(row.get("mimeType"), "application/octet-stream");
        var link = storage.download(row.get("objectKey").toString(), mime);
        return Map.of("downloadUrl", link.url(), "expiresAt", link.expiresAt(), "mimeType", mime);
    }

    private Map<String, Object> authorized(UUID user, UUID id, boolean write) {
        var row =
                repo.get(id, write)
                        .orElseThrow(
                                () ->
                                        ApiException.notFound(
                                                "SOURCE_NOT_FOUND", "Source not found."));
        if (Set.of("DELETING", "DELETED").contains(row.get("status").toString()))
            throw ApiException.notFound("SOURCE_NOT_FOUND", "Source not found.");
        if (write) notebooks.requireWrite(user, Rows.uuid(row, "notebookId"));
        else notebooks.requireRead(user, Rows.uuid(row, "notebookId"));
        return row;
    }

    @Transactional
    public Map<String, Object> raw(
            UUID user, UUID notebook, String title, String content, String key) {
        var scope = notebooks.requireWrite(user, notebook);
        repo.lockIdempotency(user, notebook, key);
        var existing = repo.idempotent(user, notebook, key);
        String checksum = AuthService.hash(content);
        if (existing.isPresent()) {
            if (!checksum.equals(existing.get().get("checksumSha256"))
                    || !title.equals(existing.get().get("title")))
                throw ApiException.conflict(
                        "IDEMPOTENCY_CONFLICT", "This key was used for different source content.");
            return visible(existing.get());
        }
        UUID id = UUID.randomUUID(), version = UUID.randomUUID();
        String objectKey =
                scope.workspaceId() + "/" + notebook + "/" + id + "/" + version + "/original.txt";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        storage.put(objectKey, data, "text/plain");
        repo.create(
                user,
                scope.workspaceId(),
                notebook,
                "RAW_TEXT",
                title,
                null,
                "text/plain",
                data.length,
                checksum,
                key,
                "QUEUED",
                id,
                version,
                objectKey);
        var row = repo.get(id, false).orElseThrow();
        enqueue(row);
        return visible(row);
    }

    @Transactional
    public Map<String, Object> url(UUID user, UUID notebook, String url, String title, String key) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_URL", "Enter a valid HTTP or HTTPS URL.");
        }
        if (!Set.of("http", "https")
                        .contains(
                                Optional.ofNullable(uri.getScheme())
                                        .orElse("")
                                        .toLowerCase(Locale.ROOT))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null)
            throw ApiException.badRequest(
                    "INVALID_URL",
                    "Enter a valid public HTTP or HTTPS URL without credentials or fragment.");
        var scope = notebooks.requireWrite(user, notebook);
        repo.lockIdempotency(user, notebook, key);
        var existing = repo.idempotent(user, notebook, key);
        if (existing.isPresent()) {
            if (!url.equals(existing.get().get("canonicalUri")))
                throw ApiException.conflict(
                        "IDEMPOTENCY_CONFLICT", "This key was used for a different URL.");
            return visible(existing.get());
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String type =
                host.equals("youtu.be")
                                || host.equals("youtube.com")
                                || host.endsWith(".youtube.com")
                        ? "YOUTUBE"
                        : "WEB";
        UUID id = UUID.randomUUID(), version = UUID.randomUUID();
        repo.create(
                user,
                scope.workspaceId(),
                notebook,
                type,
                title == null ? host : title,
                url,
                null,
                0,
                null,
                key,
                "QUEUED",
                id,
                version,
                scope.workspaceId() + "/" + notebook + "/" + id + "/" + version + "/original");
        var row = repo.get(id, false).orElseThrow();
        enqueue(row);
        return visible(row);
    }

    @Transactional
    public Map<String, Object> upload(
            UUID user,
            UUID notebook,
            String name,
            String mime,
            long size,
            String checksum,
            String key) {
        var scope = notebooks.requireWrite(user, notebook);
        String type = MIME.get(mime);
        if (type == null)
            throw new ApiException(
                    415,
                    "UNSUPPORTED_FILE_TYPE",
                    "Supported uploads are PDF, DOCX, PPTX and plain text.");
        repo.lockIdempotency(user, notebook, key);
        var existing = repo.idempotent(user, notebook, key);
        Map<String, Object> row;
        if (existing.isPresent()) {
            row = repo.get(Rows.uuid(existing.get(), "id"), true).orElseThrow();
            if (!"UPLOADING".equals(row.get("status")))
                throw ApiException.conflict(
                        "UPLOAD_ALREADY_FINALIZED", "This upload is no longer writable.");
            if (!name.equals(row.get("title"))
                    || !mime.equals(row.get("mimeType"))
                    || size != ((Number) row.get("sizeBytes")).longValue()
                    || !Objects.equals(checksum, row.get("checksumSha256")))
                throw ApiException.conflict(
                        "IDEMPOTENCY_CONFLICT", "This key was used for a different upload.");
        } else {
            UUID id = UUID.randomUUID(), version = UUID.randomUUID();
            String objectKey =
                    "staging/"
                            + scope.workspaceId()
                            + "/"
                            + notebook
                            + "/"
                            + id
                            + "/"
                            + version
                            + "/original";
            repo.create(
                    user,
                    scope.workspaceId(),
                    notebook,
                    type,
                    name,
                    null,
                    mime,
                    size,
                    checksum,
                    key,
                    "UPLOADING",
                    id,
                    version,
                    objectKey);
            row = repo.get(id, false).orElseThrow();
        }
        var upload = storage.upload(row.get("objectKey").toString(), mime);
        return Map.of(
                "source",
                visible(row),
                "uploadUrl",
                upload.url(),
                "expiresAt",
                upload.expiresAt(),
                "requiredHeaders",
                upload.headers());
    }

    @Transactional
    public Map<String, Object> complete(
            UUID user, UUID notebook, UUID id, String checksum, long size) {
        var row = authorized(user, id, true);
        if (!notebook.equals(Rows.uuid(row, "notebookId")))
            throw ApiException.notFound("SOURCE_NOT_FOUND", "Source not found in notebook.");
        if (!"UPLOADING".equals(row.get("status"))) {
            if (checksum.equals(row.get("checksumSha256"))
                    && size == ((Number) row.get("sizeBytes")).longValue()) return visible(row);
            throw ApiException.conflict(
                    "INVALID_SOURCE_STATE", "The upload has already been completed.");
        }
        if (size != ((Number) row.get("sizeBytes")).longValue()
                || (row.get("checksumSha256") != null
                        && !checksum.equals(row.get("checksumSha256"))))
            throw ApiException.badRequest(
                    "UPLOAD_MISMATCH", "Size or checksum differs from upload initialization.");
        String staging = row.get("objectKey").toString();
        String sealed =
                Rows.uuid(row, "workspaceId")
                        + "/"
                        + notebook
                        + "/"
                        + id
                        + "/"
                        + row.get("currentVersionId")
                        + "/verified/"
                        + UUID.randomUUID()
                        + "/original";
        storage.seal(staging, sealed, size, checksum, row.get("mimeType").toString());
        repo.queued(id, checksum, size, sealed);
        row = repo.get(id, false).orElseThrow();
        enqueue(row);
        return visible(row);
    }

    @Transactional
    public Map<String, Object> retry(UUID user, UUID id) {
        return retry(user, id, null);
    }

    @Transactional
    public Map<String, Object> retry(UUID user, UUID id, String key) {
        var row = authorized(user, id, true);
        // The source row lock serializes attempt creation and retry-key lookup in this transaction.
        if (key != null) {
            if (key.isBlank() || key.length() > 120)
                throw ApiException.badRequest(
                        "INVALID_IDEMPOTENCY_KEY", "Use a nonblank key of at most 120 characters.");
            var replay = repo.retryResponse(user, id, key);
            if (replay.isPresent()) return replay.get();
        }
        if (!"FAILED".equals(row.get("status")) || !Boolean.TRUE.equals(row.get("retryable")))
            throw ApiException.conflict(
                    "SOURCE_NOT_RETRYABLE", "This source cannot currently be retried.");
        UUID version = UUID.randomUUID();
        repo.newAttempt(id, version);
        row = repo.get(id, false).orElseThrow();
        enqueue(row);
        var response = visible(row);
        // Persist the public ISO timestamp representation, independent of internal JSON defaults.
        response.replaceAll(
                (field, value) ->
                        value instanceof java.time.temporal.TemporalAccessor
                                ? value.toString()
                                : value);
        if (key != null) repo.recordRetry(user, id, key, version, response);
        return response;
    }

    @Transactional
    public void delete(UUID user, UUID id) {
        var row =
                repo.get(id, true)
                        .orElseThrow(
                                () ->
                                        ApiException.notFound(
                                                "SOURCE_NOT_FOUND", "Source not found."));
        UUID notebook = Rows.uuid(row, "notebookId");
        // A repeated delete remains authorized even after the source is no longer visible.
        notebooks.requireRead(user, notebook);
        if (Set.of("DELETING", "DELETED").contains(row.get("status").toString())) return;
        notebooks.requireWrite(user, notebook);
        artifacts.invalidate(Rows.uuid(row, "workspaceId"), Rows.uuid(row, "notebookId"), id);
        repo.status(id, "DELETING", null, null, false);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceId", id);
        payload.put("sourceVersionId", row.get("currentVersionId"));
        payload.put("notebookId", notebook);
        payload.put("objectKey", row.get("objectKey"));
        outbox.publish(
                Rows.uuid(row, "workspaceId"), "Source", id, "source.delete.requested.v1", payload);
    }

    public List<Map<String, Object>> transcript(UUID user, UUID id) {
        var row = authorized(user, id, false);
        return repo.transcript(Rows.uuid(row, "currentVersionId"));
    }

    public List<UUID> requireReady(UUID user, UUID notebook, List<UUID> ids) {
        notebooks.requireRead(user, notebook);
        var ready = repo.ready(notebook);
        if (ids == null || ids.isEmpty()) return ready;
        var distinct = ids.stream().distinct().toList();
        if (!ready.containsAll(distinct))
            throw ApiException.badRequest(
                    "INVALID_SOURCE_SCOPE",
                    "All sources must be ready and belong to the notebook.");
        return distinct;
    }

    private void enqueue(Map<String, Object> row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (String field : List.of("notebookId", "objectKey", "checksumSha256", "canonicalUri"))
            payload.put(field, row.get(field));
        payload.put("sourceId", row.get("id"));
        payload.put("sourceVersionId", row.get("currentVersionId"));
        payload.put("sourceType", row.get("type"));
        String event =
                "YOUTUBE".equals(row.get("type"))
                        ? "transcript.fetch.requested.v1"
                        : "source.parse.requested.v1";
        outbox.publish(
                Rows.uuid(row, "workspaceId"), "Source", Rows.uuid(row, "id"), event, payload);
    }

    private Map<String, Object> visible(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key :
                List.of(
                        "id",
                        "workspaceId",
                        "notebookId",
                        "type",
                        "title",
                        "canonicalUri",
                        "status",
                        "failureCode",
                        "retryable",
                        "createdAt",
                        "updatedAt")) result.put(key, row.get(key));
        result.put("progress", SourceState.valueOf(row.get("status").toString()).progress());
        return result;
    }
}
