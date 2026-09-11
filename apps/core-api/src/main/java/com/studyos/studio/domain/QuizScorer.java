package com.studyos.studio.domain;

import java.text.Normalizer;
import java.util.*;

public final class QuizScorer {
    private QuizScorer() {}

    public static boolean correct(String type, Object expected, Object submitted) {
        if (submitted == null || expected == null) return false;
        return switch (type) {
            case "SHORT_ANSWER" ->
                    expected instanceof String a
                            && submitted instanceof String b
                            && normalize(a).equals(normalize(b));
            case "MCQ" -> expected instanceof String && expected.equals(submitted);
            case "TRUE_FALSE" -> expected instanceof Boolean && expected.equals(submitted);
            case "MULTI_SELECT" ->
                    expected instanceof List<?> a
                            && submitted instanceof List<?> b
                            && !a.isEmpty()
                            && new HashSet<>(a).size() == a.size()
                            && new HashSet<>(b).size() == b.size()
                            && new HashSet<>(a).equals(new HashSet<>(b));
            default -> throw new IllegalArgumentException("Unsupported question type");
        };
    }

    public static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }
}
