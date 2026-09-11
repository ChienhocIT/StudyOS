package com.studyos.conversation.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.studyos.conversation.application.port.ConversationRepository;
import com.studyos.notebook.application.NotebookAccess;
import com.studyos.shared.security.JwtTokens;
import com.studyos.shared.usage.AiUsagePolicy;
import com.studyos.shared.web.ApiException;
import com.studyos.source.application.SourceLookup;
import java.util.*;
import org.junit.jupiter.api.*;

class StreamingAuthorizationTest {
    private final ConversationRepository repo = mock(ConversationRepository.class);
    private final NotebookAccess notebooks = mock(NotebookAccess.class);
    private final SourceLookup sources = mock(SourceLookup.class);
    private final AiUsagePolicy usage = mock(AiUsagePolicy.class);
    private final ConversationService service =
            new ConversationService(
                    repo,
                    notebooks,
                    sources,
                    mock(JwtTokens.class),
                    "http://localhost:8080",
                    usage);
    private final UUID user = UUID.randomUUID(),
            conversation = UUID.randomUUID(),
            request = UUID.randomUUID(),
            notebook = UUID.randomUUID(),
            source = UUID.randomUUID();
    private final Map<String, Object> generation = new HashMap<>();

    @BeforeEach
    void setup() {
        generation.put("userId", user);
        generation.put("status", "STREAMING");
        generation.put("sourceIdsJson", List.of(source.toString()));
        when(repo.generation(conversation, request, true)).thenReturn(Optional.of(generation));
        when(repo.get(conversation, false))
                .thenReturn(Optional.of(Map.of("userId", user, "notebookId", notebook)));
        doAnswer(
                        invocation -> {
                            generation.put("status", invocation.getArgument(3));
                            return null;
                        })
                .when(repo)
                .finish(
                        eq(conversation),
                        eq(request),
                        anyString(),
                        anyString(),
                        nullable(String.class));
    }

    @Test
    void membershipRevocationCancelsGenerationAndReleasesReservationExactlyOnce() {
        when(notebooks.requireRead(user, notebook))
                .thenThrow(ApiException.notFound("NOTEBOOK_NOT_FOUND", "Notebook not found."));
        assertThat(service.active(conversation, request)).isFalse();
        assertThat(service.active(conversation, request)).isFalse();
        verify(repo).finish(conversation, request, "", "CANCELLED", null);
        verify(usage).failed(user, "CHAT", conversation + ":" + request);
    }

    @Test
    void archivedNotebookOrRemovedSourceStopsEvenAnExistingStream() {
        when(notebooks.requireWrite(user, notebook))
                .thenThrow(ApiException.conflict("NOTEBOOK_ARCHIVED", "Archived."));
        assertThat(service.canReceive(user, conversation, request)).isFalse();
        verify(usage).failed(user, "CHAT", conversation + ":" + request);
        reset(notebooks, usage);
        generation.put("status", "STREAMING");
        when(sources.requireReady(user, notebook, List.of(source)))
                .thenThrow(ApiException.badRequest("INVALID_SOURCE_SCOPE", "Source unavailable."));
        assertThat(service.active(conversation, request)).isFalse();
        verify(usage).failed(user, "CHAT", conversation + ":" + request);
    }

    @Test
    void authorizedFramesStillFlowAndAnotherUserCannotCancelTheOwner() {
        assertThat(service.active(conversation, request)).isTrue();
        assertThat(service.canReceive(user, conversation, request)).isTrue();
        assertThat(service.canReceive(UUID.randomUUID(), conversation, request)).isFalse();
        verify(repo, never()).finish(any(), any(), any(), any(), any());
        verifyNoInteractions(usage);
    }

    @Test
    void completionAfterRevocationStoresNoAnswerOrUsageSuccess() {
        when(notebooks.requireRead(user, notebook))
                .thenThrow(ApiException.forbidden("MEMBERSHIP_REVOKED", "Access revoked."));
        var turn =
                new ConversationService.Turn(
                        user,
                        UUID.randomUUID(),
                        notebook,
                        conversation,
                        request,
                        UUID.randomUUID(),
                        "question",
                        "ASK",
                        List.of(source),
                        "trace",
                        false,
                        List.of());
        assertThat(service.complete(turn, Map.of("content", "Private answer")))
                .containsEntry("status", "CANCELLED");
        verify(repo).finish(conversation, request, "", "CANCELLED", null);
        verify(usage).failed(user, "CHAT", conversation + ":" + request);
        verify(usage, never()).complete(any(), any(), any(), any(), any());
    }
}
