package com.sentinel.kms.assistant;

import com.sentinel.kms.common.EvidenceReference;
import com.sentinel.kms.kb.KnowledgeAccessService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConversationServiceEvidenceTest {

    private static final EvidenceReference REFERENCE = new EvidenceReference(
            "TI-1", "revision-1", 1, "snapshot-1", 0, 8,
            "https://example.test", "2026-09-05", "APPROVED");
    private static final SourceEvidenceReference SOURCE = new SourceEvidenceReference(
            "upload-1", "upload-revision-1", "upload-1:upload-revision-1");

    @Test
    void assistantProvenanceRoundTripsThroughTheTranscriptDto() {
        ChatMessage message = assistantMessage();

        ConversationService.MessageDto dto = ConversationService.MessageDto.from(message, Set.of("TI-1"));

        assertThat(dto.citations()).containsExactly("TI-1");
        assertThat(dto.citationReferences()).containsExactly(REFERENCE);
        assertThat(dto.content()).isEqualTo("Safe answer [TI-1]");
    }

    @Test
    void revokedEvidenceRedactsTheWholeHistoricalAssistantMessage() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        KnowledgeAccessService access = mock(KnowledgeAccessService.class);
        MessageCitationReferenceStore references = mock(MessageCitationReferenceStore.class);
        MessageSourceReferenceStore sourceReferences = mock(MessageSourceReferenceStore.class);
        Conversation conversation = conversation();
        ChatMessage assistant = assistantMessage();
        when(conversations.findById("conversation-1")).thenReturn(Optional.of(conversation));
        when(messages.findByConversationIdOrderByCreatedAtAsc("conversation-1")).thenReturn(List.of(assistant));
        when(references.loadByMessageIds(List.of("message-1"))).thenReturn(Map.of("message-1", List.of(REFERENCE)));
        when(sourceReferences.loadByMessageIds(List.of("message-1"))).thenReturn(Map.of());
        when(access.readableArticleIds("analyst-1", Set.of("TI-1"))).thenReturn(Set.of());
        ConversationService service = new ConversationService(conversations, messages, access, references, sourceReferences);

        List<ConversationService.MessageDto> transcript = service.messages("analyst-1", "conversation-1");

        assertThat(transcript).singleElement().satisfies(message -> {
            assertThat(message.content()).isEqualTo(ConversationService.REVOKED_EVIDENCE_MESSAGE);
            assertThat(message.citations()).isEmpty();
            assertThat(message.citationReferences()).isEmpty();
        });
        verify(access).readableArticleIds("analyst-1", Set.of("TI-1"));
        verify(references).loadByMessageIds(List.of("message-1"));
    }

    @Test
    void staleNormalizedCitationIsRedactedEvenWhenItsArticleIdIsStillReadable() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        KnowledgeAccessService access = mock(KnowledgeAccessService.class);
        MessageCitationReferenceStore references = mock(MessageCitationReferenceStore.class);
        MessageSourceReferenceStore sourceReferences = mock(MessageSourceReferenceStore.class);
        Conversation conversation = conversation();
        ChatMessage assistant = assistantMessage();
        when(conversations.findById("conversation-1")).thenReturn(Optional.of(conversation));
        when(messages.findByConversationIdOrderByCreatedAtAsc("conversation-1")).thenReturn(List.of(assistant));
        when(references.loadByMessageIds(List.of("message-1"))).thenReturn(Map.of("message-1", List.of(REFERENCE)));
        when(references.resolveCurrentApproved("analyst-1", List.of(REFERENCE)))
                .thenThrow(new IllegalArgumentException("superseded evidence"));
        when(sourceReferences.loadByMessageIds(List.of("message-1"))).thenReturn(Map.of());
        when(access.readableArticleIds("analyst-1", Set.of("TI-1"))).thenReturn(Set.of("TI-1"));
        ConversationService service = new ConversationService(conversations, messages, access, references, sourceReferences);

        List<ConversationService.MessageDto> transcript = service.messages("analyst-1", "conversation-1");

        assertThat(transcript).singleElement().extracting(ConversationService.MessageDto::content)
                .isEqualTo(ConversationService.REVOKED_EVIDENCE_MESSAGE);
        verify(references).resolveCurrentApproved("analyst-1", List.of(REFERENCE));
    }

    @Test
    void revokedUploadEvidenceRedactsTheWholeHistoricalAssistantMessage() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        KnowledgeAccessService access = mock(KnowledgeAccessService.class);
        MessageCitationReferenceStore references = mock(MessageCitationReferenceStore.class);
        MessageSourceReferenceStore sourceReferences = mock(MessageSourceReferenceStore.class);
        Conversation conversation = conversation();
        ChatMessage assistant = assistantMessage();
        assistant.setCitations(List.of());
        assistant.setCitationReferences(List.of());
        when(conversations.findById("conversation-1")).thenReturn(Optional.of(conversation));
        when(messages.findByConversationIdOrderByCreatedAtAsc("conversation-1")).thenReturn(List.of(assistant));
        when(references.loadByMessageIds(List.of("message-1"))).thenReturn(Map.of());
        when(sourceReferences.loadByMessageIds(List.of("message-1")))
                .thenReturn(Map.of("message-1", List.of(SOURCE)));
        when(access.canReadSource("analyst-1", SOURCE.sourceId(), SOURCE.sourceRevisionId(), SOURCE.sourceKey()))
                .thenReturn(new com.sentinel.kms.common.AccessDecision(false, "SOURCE_NOT_READABLE", null, null, null));
        ConversationService service = new ConversationService(conversations, messages, access, references, sourceReferences);

        List<ConversationService.MessageDto> transcript = service.messages("analyst-1", "conversation-1");

        assertThat(transcript).singleElement().satisfies(message -> {
            assertThat(message.content()).isEqualTo(ConversationService.REVOKED_EVIDENCE_MESSAGE);
            assertThat(message.citations()).isEmpty();
            assertThat(message.citationReferences()).isEmpty();
        });
        verify(access).canReadSource("analyst-1", SOURCE.sourceId(), SOURCE.sourceRevisionId(), SOURCE.sourceKey());
    }

    @Test
    void unboundLegacyAssistantContentIsRedactedRatherThanAssumedToBeSafe() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        KnowledgeAccessService access = mock(KnowledgeAccessService.class);
        MessageCitationReferenceStore references = mock(MessageCitationReferenceStore.class);
        MessageSourceReferenceStore sourceReferences = mock(MessageSourceReferenceStore.class);
        Conversation conversation = conversation();
        ChatMessage assistant = assistantMessage();
        assistant.setCitations(List.of()); // A pre-provenance upload answer has no trusted article-id signal.
        assistant.setCitationReferences(List.of());
        when(conversations.findById("conversation-1")).thenReturn(Optional.of(conversation));
        when(messages.findByConversationIdOrderByCreatedAtAsc("conversation-1")).thenReturn(List.of(assistant));
        when(references.loadByMessageIds(List.of("message-1"))).thenReturn(Map.of());
        when(sourceReferences.loadByMessageIds(List.of("message-1"))).thenReturn(Map.of());
        ConversationService service = new ConversationService(conversations, messages, access, references, sourceReferences);

        List<ConversationService.MessageDto> transcript = service.messages("analyst-1", "conversation-1");

        assertThat(transcript).singleElement().extracting(ConversationService.MessageDto::content)
                .isEqualTo(ConversationService.REVOKED_EVIDENCE_MESSAGE);
        verifyNoInteractions(access);
    }

    @Test
    void revokedUploadEvidenceIsNotReplayedIntoRecentHistory() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        KnowledgeAccessService access = mock(KnowledgeAccessService.class);
        MessageCitationReferenceStore references = mock(MessageCitationReferenceStore.class);
        MessageSourceReferenceStore sourceReferences = mock(MessageSourceReferenceStore.class);
        ChatMessage currentUserTurn = new ChatMessage();
        currentUserTurn.setId("message-current");
        currentUserTurn.setConversationId("conversation-1");
        currentUserTurn.setRole("user");
        currentUserTurn.setContent("Follow up");
        ChatMessage assistant = assistantMessage();
        assistant.setCitations(List.of());
        assistant.setCitationReferences(List.of());
        when(messages.findTop10ByConversationIdOrderByCreatedAtDesc("conversation-1"))
                .thenReturn(List.of(currentUserTurn, assistant));
        when(conversations.findById("conversation-1")).thenReturn(Optional.of(conversation()));
        when(references.loadByMessageIds(List.of("message-1"))).thenReturn(Map.of());
        when(sourceReferences.loadByMessageIds(List.of("message-1")))
                .thenReturn(Map.of("message-1", List.of(SOURCE)));
        when(access.canReadSource("analyst-1", SOURCE.sourceId(), SOURCE.sourceRevisionId(), SOURCE.sourceKey()))
                .thenReturn(new com.sentinel.kms.common.AccessDecision(false, "SOURCE_REVOKED", null, null, null));
        ConversationService service = new ConversationService(conversations, messages, access, references, sourceReferences);

        List<ChatMessage> history = service.recentHistory("analyst-1", "conversation-1", 4);

        assertThat(history).singleElement().satisfies(message -> {
            assertThat(message.getContent()).isEqualTo(ConversationService.REVOKED_EVIDENCE_MESSAGE);
            assertThat(message.getCitations()).isEmpty();
            assertThat(message.getCitationReferences()).isEmpty();
        });
        verify(access).canReadSource("analyst-1", SOURCE.sourceId(), SOURCE.sourceRevisionId(), SOURCE.sourceKey());
    }

    @Test
    void historyForAnotherUsersConversationFailsClosedBeforeReadingMessages() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        KnowledgeAccessService access = mock(KnowledgeAccessService.class);
        MessageCitationReferenceStore references = mock(MessageCitationReferenceStore.class);
        MessageSourceReferenceStore sourceReferences = mock(MessageSourceReferenceStore.class);
        Conversation ownedBySomeoneElse = conversation();
        ownedBySomeoneElse.setUserSub("other-user");
        when(conversations.findById("conversation-1")).thenReturn(Optional.of(ownedBySomeoneElse));
        ConversationService service = new ConversationService(conversations, messages, access, references, sourceReferences);

        List<ChatMessage> history = service.recentHistory("analyst-1", "conversation-1", 4);

        assertThat(history).isEmpty();
        verify(messages, never()).findTop10ByConversationIdOrderByCreatedAtDesc(anyString());
    }

    @Test
    void appendAssistantPersistsOnlyCanonicalNormalizedEvidenceWithoutChangingLegacyIds() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        KnowledgeAccessService access = mock(KnowledgeAccessService.class);
        MessageCitationReferenceStore references = mock(MessageCitationReferenceStore.class);
        MessageSourceReferenceStore sourceReferences = mock(MessageSourceReferenceStore.class);
        ConversationService service = new ConversationService(conversations, messages, access, references, sourceReferences);
        Conversation conversation = conversation();
        EvidenceReference canonical = new EvidenceReference(
                "TI-1", "revision-1", 2, "snapshot-1", 0, 8,
                "https://authoritative.example", "2026-09-06", "approved");
        when(messages.saveAndFlush(any(ChatMessage.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(references.bind(eq("analyst-1"), anyString(), eq(List.of(REFERENCE))))
                .thenReturn(List.of(canonical));
        when(sourceReferences.bind(eq("analyst-1"), anyString(), eq(List.of()))).thenReturn(List.of());

        ChatMessage saved = service.appendAssistant("analyst-1", conversation,
                "Safe answer [TI-1]", List.of("TI-1"), List.of(REFERENCE), List.of());

        assertThat(saved.getCitations()).containsExactly("TI-1");
        assertThat(saved.getCitationReferences()).containsExactly(canonical);
        verify(messages).saveAndFlush(any(ChatMessage.class));
        verify(references).bind(eq("analyst-1"), eq(saved.getId()), eq(List.of(REFERENCE)));
        verify(sourceReferences).bind(eq("analyst-1"), eq(saved.getId()), eq(List.of()));
        verify(conversations).touch(conversation.getId());
    }

    @Test
    void appendAssistantPersistsAuthorizedUploadProvenanceForLaterRevocationChecks() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        KnowledgeAccessService access = mock(KnowledgeAccessService.class);
        MessageCitationReferenceStore references = mock(MessageCitationReferenceStore.class);
        MessageSourceReferenceStore sourceReferences = mock(MessageSourceReferenceStore.class);
        ConversationService service = new ConversationService(conversations, messages, access, references, sourceReferences);
        Conversation conversation = conversation();
        when(messages.saveAndFlush(any(ChatMessage.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(references.bind(eq("analyst-1"), anyString(), eq(List.of()))).thenReturn(List.of());
        when(sourceReferences.bind(eq("analyst-1"), anyString(), eq(List.of(SOURCE))))
                .thenReturn(List.of(SOURCE));

        ChatMessage saved = service.appendAssistant("analyst-1", conversation,
                "Rotate the uploaded credential.", List.of(), List.of(), List.of(SOURCE));

        verify(sourceReferences).bind(eq("analyst-1"), eq(saved.getId()), eq(List.of(SOURCE)));
        verify(conversations).touch(conversation.getId());
    }

    @Test
    void failedCitationBindingDoesNotTouchTheConversation() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        KnowledgeAccessService access = mock(KnowledgeAccessService.class);
        MessageCitationReferenceStore references = mock(MessageCitationReferenceStore.class);
        MessageSourceReferenceStore sourceReferences = mock(MessageSourceReferenceStore.class);
        ConversationService service = new ConversationService(conversations, messages, access, references, sourceReferences);
        when(messages.saveAndFlush(any(ChatMessage.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(references.bind(eq("analyst-1"), anyString(), eq(List.of(REFERENCE))))
                .thenThrow(new IllegalArgumentException("citation does not resolve to current approved evidence"));
        when(sourceReferences.bind(eq("analyst-1"), anyString(), eq(List.of()))).thenReturn(List.of());

        assertThatThrownBy(() -> service.appendAssistant("analyst-1", conversation(),
                "Safe answer [TI-1]", List.of("TI-1"), List.of(REFERENCE), List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        verify(conversations, never()).touch(anyString());
    }

    @Test
    void appendAssistantRejectsASubjectThatDoesNotOwnTheConversationBeforeWriting() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        KnowledgeAccessService access = mock(KnowledgeAccessService.class);
        MessageCitationReferenceStore references = mock(MessageCitationReferenceStore.class);
        MessageSourceReferenceStore sourceReferences = mock(MessageSourceReferenceStore.class);
        ConversationService service = new ConversationService(conversations, messages, access, references, sourceReferences);

        assertThatThrownBy(() -> service.appendAssistant("other-user", conversation(), "answer", List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        verify(messages, never()).saveAndFlush(any(ChatMessage.class));
        verify(conversations, never()).touch(anyString());
    }

    @Test
    void appendAssistantRejectsLegacyArticleIdsWithoutMatchingExactReferencesBeforeWriting() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        KnowledgeAccessService access = mock(KnowledgeAccessService.class);
        MessageCitationReferenceStore references = mock(MessageCitationReferenceStore.class);
        MessageSourceReferenceStore sourceReferences = mock(MessageSourceReferenceStore.class);
        ConversationService service = new ConversationService(conversations, messages, access, references, sourceReferences);

        assertThatThrownBy(() -> service.appendAssistant("analyst-1", conversation(),
                "answer [TI-1]", List.of("TI-1"), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        verify(messages, never()).saveAndFlush(any(ChatMessage.class));
        verify(conversations, never()).touch(anyString());
    }

    private static ChatMessage assistantMessage() {
        ChatMessage message = new ChatMessage();
        message.setId("message-1");
        message.setConversationId("conversation-1");
        message.setRole("assistant");
        message.setContent("Safe answer [TI-1]");
        message.setCitations(List.of("TI-1"));
        message.setCitationReferences(List.of(REFERENCE));
        return message;
    }

    private static Conversation conversation() {
        Conversation conversation = new Conversation();
        conversation.setId("conversation-1");
        conversation.setUserSub("analyst-1");
        return conversation;
    }
}
