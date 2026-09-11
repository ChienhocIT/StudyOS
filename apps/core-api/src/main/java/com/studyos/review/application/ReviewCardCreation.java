package com.studyos.review.application;

import java.util.UUID;

public interface ReviewCardCreation {
    UUID fromVocabulary(UUID userId,UUID notebookId,UUID vocabularyId,String front,String back);
}
