package com.studyos.studio.application;

import com.studyos.shared.web.ApiException;
import com.studyos.studio.application.port.StudioStore;
import java.util.*;

/** Validates AI proposals against current tenant-scoped durable source evidence. */
public final class ArtifactValidator {
    private final StudioStore store;

    public ArtifactValidator(StudioStore store) {
        this.store = store;
    }

    public Map<String, Object> validate(
            String type,
            Map<String, Object> artifact,
            UUID workspace,
            UUID notebook,
            List<UUID> sourceIds) {
        text(artifact.get("title"), 240);
        if (type.equals("QUIZ")) {
            var questions = objects(artifact.get("questions"));
            bounded(questions);
            for (var q : questions) {
                text(q.get("prompt"), 4000);
                String kind = Objects.toString(q.get("type"));
                if (!Set.of("MCQ", "MULTI_SELECT", "TRUE_FALSE", "SHORT_ANSWER").contains(kind))
                    throw invalid();
                var answer = object(q.get("answer"));
                Object correct = answer.get("correctAnswer");
                if (kind.equals("TRUE_FALSE")) {
                    if (!(correct instanceof Boolean)) throw invalid();
                } else if (kind.equals("MULTI_SELECT")) {
                    if (!(correct instanceof List<?> list) || list.isEmpty()) throw invalid();
                    var options = choices(answer.get("options"));
                    if (!options.containsAll(list) || new HashSet<>(list).size() != list.size())
                        throw invalid();
                } else {
                    text(correct, 4000);
                    if (kind.equals("MCQ") && !choices(answer.get("options")).contains(correct))
                        throw invalid();
                }
                var refs = objects(q.get("sourceRefs"));
                if (refs.isEmpty() || refs.size() > 20) throw invalid();
                List<UUID> chunks = new ArrayList<>();
                for (var ref : refs) {
                    UUID chunk = checkReference(ref, workspace, notebook, sourceIds);
                    chunks.add(chunk);
                }
                q.put("conceptIds", store.chunkConcepts(notebook, chunks));
                q.put("answer", answer);
            }
            artifact.put("questions", questions);
        } else if (type.equals("FLASHCARDS")) {
            var cards = objects(artifact.get("cards"));
            bounded(cards);
            for (var card : cards) {
                text(card.get("front"), 4000);
                text(card.get("back"), 4000);
                UUID chunk = uuid(card.get("sourceChunkId"));
                checkChunk(workspace, notebook, chunk, sourceIds);
                card.put("sourceChunkId", chunk);
                var concepts = store.chunkConcepts(notebook, List.of(chunk));
                card.put("conceptId", concepts.isEmpty() ? null : concepts.getFirst());
            }
            artifact.put("cards", cards);
        } else if (type.equals("STUDY_GUIDE")) {
            String content = text(artifact.get("content"), 40000);
            var refs = objects(artifact.get("sourceRefs"));
            if (refs.isEmpty() || refs.size() > 20) throw invalid();
            Set<String> keys = new HashSet<>();
            for (var ref : refs) {
                checkReference(ref, workspace, notebook, sourceIds);
                String key = text(ref.get("key"), 20);
                if (!key.matches("C[1-9][0-9]*") || !keys.add(key)) throw invalid();
            }
            var markers = java.util.regex.Pattern.compile("\\[C([0-9]+)\\]").matcher(content);
            boolean cited = false;
            while (markers.find()) {
                if (!keys.contains("C" + markers.group(1))) throw invalid();
                cited = true;
            }
            if (!cited) throw invalid();
        } else throw invalid();
        return artifact;
    }

    private UUID checkReference(
            Map<String, Object> ref, UUID workspace, UUID notebook, List<UUID> selected) {
        UUID chunk = uuid(ref.get("chunkId"));
        if (ref.get("sourceId") != null) {
            UUID source = uuid(ref.get("sourceId"));
            if (!selected.contains(source)) throw invalid();
            checkChunk(workspace, notebook, chunk, List.of(source));
        } else checkChunk(workspace, notebook, chunk, selected);
        return chunk;
    }

    private void checkChunk(UUID workspace, UUID notebook, UUID chunk, List<UUID> sources) {
        if (!store.validChunk(workspace, notebook, chunk, sources))
            throw ApiException.badRequest(
                    "ARTIFACT_PROVENANCE_INVALID",
                    "Generated artifact references unavailable source evidence.");
    }

    private List<String> choices(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() < 2 || list.size() > 10) throw invalid();
        List<String> choices = new ArrayList<>();
        for (Object option : (List<?>) raw) choices.add(text(option, 1000));
        if (new HashSet<>(choices).size() != choices.size()) throw invalid();
        return choices;
    }

    private void bounded(List<?> list) {
        if (list.isEmpty() || list.size() > 50) throw invalid();
    }

    private String text(Object value, int max) {
        if (!(value instanceof String s) || s.isBlank() || s.length() > max) throw invalid();
        return (String) value;
    }

    static Map<String, Object> object(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) throw invalid();
        Map<String, Object> result = new LinkedHashMap<>();
        m.forEach((k, v) -> result.put(k.toString(), v));
        return result;
    }

    static List<Map<String, Object>> objects(Object raw) {
        if (!(raw instanceof List<?> list)) throw invalid();
        return list.stream().map(ArtifactValidator::object).toList();
    }

    static UUID uuid(Object raw) {
        try {
            return UUID.fromString(raw.toString());
        } catch (Exception e) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        throw ApiException.badRequest(
                "ARTIFACT_OUTPUT_INVALID", "Generated artifact is malformed.");
    }
}
