package com.studyos.language.application;

import com.studyos.language.application.port.LanguageAnalyzer;
import com.studyos.language.application.port.LanguageStore;
import com.studyos.notebook.application.NotebookAccess;
import com.studyos.review.application.ReviewCardCreation;
import com.studyos.shared.persistence.Rows;
import com.studyos.shared.web.ApiException;
import com.studyos.source.application.SourceService;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LanguageService {
    private final LanguageStore store;
    private final LanguageAnalyzer analyzer;
    private final SourceService sources;
    private final NotebookAccess notebooks;
    private final ReviewCardCreation cards;
    private final com.studyos.shared.usage.AiUsagePolicy usage;

    public LanguageService(
            LanguageStore store,
            LanguageAnalyzer analyzer,
            SourceService sources,
            NotebookAccess notebooks,
            ReviewCardCreation cards,
            com.studyos.shared.usage.AiUsagePolicy usage) {
        this.store = store;
        this.analyzer = analyzer;
        this.sources = sources;
        this.notebooks = notebooks;
        this.cards = cards;
        this.usage = usage;
    }

    public Object analyze(
            UUID user, UUID source, UUID segment, String text, String target, String level) {
        var info = sources.get(user, source);
        UUID notebook = Rows.uuid(info, "notebookId");
        var scope = notebooks.requireRead(user, notebook);
        if (!"READY".equals(info.get("status")))
            throw ApiException.conflict("SOURCE_NOT_READY", "Source is not ready for analysis.");
        if (segment != null && !store.validSegment(source, segment, text))
            throw ApiException.badRequest(
                    "TRANSCRIPT_SCOPE_INVALID",
                    "Selected transcript segment does not match the source sentence.");
        if (!Set.of("A1", "A2", "B1", "B2", "C1", "C2").contains(level))
            throw ApiException.badRequest("VALIDATION_FAILED", "Invalid learner level.");
        String key = UUID.randomUUID().toString();
        usage.reserve(user, scope.workspaceId(), "LANGUAGE", key);
        try {
            var result =
                    analyzer.analyze(
                            user, scope.workspaceId(), notebook, source, text, target, level);
            Map<String, Object> metrics = new LinkedHashMap<>();
            if (result.get("usage") instanceof Map<?, ?> values)
                values.forEach((k, v) -> metrics.put(k.toString(), v));
            usage.complete(user, "LANGUAGE", key, metrics, key);
            return result;
        } catch (RuntimeException ex) {
            usage.failed(user, "LANGUAGE", key);
            throw ex;
        }
    }

    public Object vocabulary(UUID user, UUID notebook) {
        if (notebook != null) notebooks.requireRead(user, notebook);
        return store.vocabulary(user, notebook);
    }

    @Transactional
    public Map<String, Object> save(
            UUID user,
            UUID notebook,
            UUID source,
            UUID segment,
            String term,
            String meaning,
            String context,
            String sourceLanguage,
            String targetLanguage,
            boolean createCard) {
        notebooks.requireWrite(user, notebook);
        if (source != null) {
            var info = sources.get(user, source);
            if (!Rows.uuid(info, "notebookId").equals(notebook))
                throw ApiException.badRequest(
                        "SOURCE_SCOPE_INVALID", "Source is outside this notebook.");
        }
        if (segment != null && (source == null || !store.validSegment(source, segment, null)))
            throw ApiException.badRequest(
                    "TRANSCRIPT_SCOPE_INVALID", "Transcript segment is outside this source.");
        String normalized =
                Normalizer.normalize(term.strip(), Normalizer.Form.NFKC)
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("\\s+", " ");
        var row =
                new LinkedHashMap<>(
                        store.save(
                                user,
                                notebook,
                                source,
                                segment,
                                term.strip(),
                                normalized,
                                meaning,
                                context,
                                sourceLanguage,
                                targetLanguage));
        if (createCard) {
            if (meaning == null || meaning.isBlank())
                throw ApiException.badRequest(
                        "VOCABULARY_MEANING_REQUIRED",
                        "Add a meaning before creating a flashcard.");
            UUID card =
                    cards.fromVocabulary(
                            user,
                            notebook,
                            Rows.uuid(row, "id"),
                            row.get("term").toString(),
                            Objects.toString(row.get("meaning"), meaning));
            row.put("flashcardId", card);
        }
        return row;
    }
}
