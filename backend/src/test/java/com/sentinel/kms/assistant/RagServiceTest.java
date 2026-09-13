package com.sentinel.kms.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.kms.common.AccessDecision;
import com.sentinel.kms.common.EvidenceReference;
import com.sentinel.kms.common.OpenRouterModelCatalog;
import com.sentinel.kms.kb.Article;
import com.sentinel.kms.kb.EvidenceReferenceResolver;
import com.sentinel.kms.kb.KnowledgeAccessService;
import com.sentinel.kms.kb.ArticleRepository;
import com.sentinel.kms.kb.IocLookupService;
import com.sentinel.kms.vectorstore.EmbeddingIndexService;
import com.sentinel.kms.vectorstore.Reranker;
import com.sentinel.kms.vectorstore.RetrievedChunk;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagServiceTest {

    private static final String ABSTENTION =
            "I couldn't find anything relevant in the knowledge base for that question.";

    @Test
    void chatAbstainsBeforeAnyProviderCallWhenAuthorizedEvidenceIsEmpty() {
        Fixture fixture = new Fixture();
        when(fixture.index.hybridSearch(eq("unknown incident"), eq(6), eq(0.30), eq("analyst-1"), any()))
                .thenReturn(List.of());

        ChatResponse response = fixture.service().chat(new ChatRequest("unknown incident", null), "analyst-1");

        assertThat(response.blocks()).containsExactly(Block.text(ABSTENTION));
        assertThat(response.citations()).isEmpty();
        verify(fixture.conversations).appendAssistant("analyst-1", fixture.conversation, ABSTENTION,
                List.of(), List.of(), List.of());
        verifyNoInteractions(fixture.chatModel, fixture.rewriterModel, fixture.rerankerModel);
    }

    @Test
    void streamAbstainsBeforeAnyProviderCallWhenAuthorizedEvidenceIsEmpty() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.index.hybridSearch(eq("unknown incident"), eq(6), eq(0.30), eq("analyst-1"), any()))
                .thenReturn(List.of());

        List<ServerSentEvent<String>> events = fixture.service()
                .stream(new ChatRequest("unknown incident", null), "analyst-1")
                .collectList()
                .block();

        assertThat(events).hasSize(2);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode delta = mapper.readTree(events.get(0).data());
        JsonNode done = mapper.readTree(events.get(1).data());
        assertThat(delta.path("type").asText()).isEqualTo("delta");
        assertThat(delta.path("text").asText()).isEqualTo(ABSTENTION);
        assertThat(done.path("type").asText()).isEqualTo("done");
        assertThat(done.path("citations")).isEmpty();
        verify(fixture.conversations).appendAssistant("analyst-1", fixture.conversation, ABSTENTION,
                List.of(), List.of(), List.of());
        verifyNoInteractions(fixture.chatModel, fixture.rewriterModel, fixture.rerankerModel);
    }

    @Test
    void orphanOrUnauthorizedArticleMetadataCannotUnlockAProviderCall() {
        Fixture fixture = new Fixture();
        RetrievedChunk orphan = new RetrievedChunk("stale evidence", 0.9, Map.of("article_id", "ORPHAN-1"));
        when(fixture.index.hybridSearch(eq("unknown incident"), eq(6), eq(0.30), eq("analyst-1"), any()))
                .thenReturn(List.of(orphan));
        when(fixture.access.readableArticleIds("analyst-1", Set.of("ORPHAN-1"))).thenReturn(Set.of());

        ChatResponse response = fixture.service().chat(new ChatRequest("unknown incident", null), "analyst-1");

        assertThat(response.blocks()).containsExactly(Block.text(ABSTENTION));
        assertThat(response.citations()).isEmpty();
        verifyNoInteractions(fixture.chatModel, fixture.rewriterModel, fixture.rerankerModel);
    }

    @Test
    void articleWithAnExistingIdButNoServerBoundRevisionAbstainsBeforeAnyProviderCall() {
        Fixture fixture = new Fixture();
        RetrievedChunk forged = new RetrievedChunk("untrusted passage", 0.9, Map.of(
                "article_id", "TI-1",
                "article_revision_id", "forged-revision",
                "article_revision_number", 1,
                "evidence_snapshot_id", "forged-snapshot",
                "source_span_start", 0,
                "source_span_end", 8));
        Article article = new Article();
        article.setId("TI-1");
        article.setTitle("Governed report");
        when(fixture.index.hybridSearch(eq("forged revision"), eq(10), eq(0.30), eq("analyst-1"), any()))
                .thenReturn(List.of(forged));
        when(fixture.access.readableArticleIds("analyst-1", Set.of("TI-1"))).thenReturn(Set.of("TI-1"));
        when(fixture.articles.findAllById(Set.of("TI-1"))).thenReturn(List.of(article));
        when(fixture.evidenceResolver.resolveCurrentApproved(eq("analyst-1"), any()))
                .thenReturn(Optional.empty());
        when(fixture.conversations.openForTurn("analyst-1", null, "forged revision"))
                .thenReturn(fixture.conversation);
        when(fixture.conversations.recentHistory("analyst-1", fixture.conversation.getId(), 4)).thenReturn(List.of());

        ChatResponse response = fixture.service().chat(new ChatRequest("forged revision", null), "analyst-1");

        assertThat(response.blocks()).containsExactly(Block.text(ABSTENTION));
        verify(fixture.conversations).appendAssistant("analyst-1", fixture.conversation, ABSTENTION,
                List.of(), List.of(), List.of());
        verifyNoInteractions(fixture.chatModel, fixture.rewriterModel, fixture.rerankerModel);
    }

    @Test
    void chatResolvesOnlyExactEvidenceTokensToLegacyCitationsAndProvenance() {
        Fixture fixture = new Fixture();
        Fixture.configureGovernedEvidence(fixture);
        String valid = "[[evidence:TI-1|revision-1|snapshot-1|0-8]]";
        String forged = "[[evidence:TI-1|wrong-revision|snapshot-1|0-8]]";
        when(fixture.chatModel.call(any(Prompt.class))).thenReturn(Fixture.modelResponse(
                "{\"blocks\":[{\"type\":\"text\",\"text\":\"Fact " + valid + " " + forged
                        + " [FAKE-1]\"}],\"citations\":[\"FAKE-1\"]}"));

        ChatResponse response = fixture.service().chat(new ChatRequest("known incident", null), "analyst-1");

        assertThat(response.citations()).containsExactly("TI-1");
        assertThat(response.citationReferences()).hasSize(1);
        assertThat(response.blocks().get(0).text())
                .contains("[TI-1]")
                .doesNotContain("[[evidence:")
                .doesNotContain("wrong-revision")
                .doesNotContain("FAKE-1");
    }

    @Test
    void chatPersistsAllServerBoundGroundingWhenTheModelOmitsItsCitationToken() {
        Fixture fixture = new Fixture();
        Fixture.configureGovernedEvidence(fixture);
        when(fixture.chatModel.call(any(Prompt.class))).thenReturn(Fixture.modelResponse(
                "{\"blocks\":[{\"type\":\"text\",\"text\":\"A grounded answer without a token.\"}],\"citations\":[]}"));

        ChatResponse response = fixture.service().chat(new ChatRequest("known incident", null), "analyst-1");

        assertThat(response.citations()).isEmpty();
        assertThat(response.citationReferences()).isEmpty();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvidenceReference>> persisted = ArgumentCaptor.forClass(List.class);
        verify(fixture.conversations).appendAssistant(eq("analyst-1"), eq(fixture.conversation),
                eq("A grounded answer without a token."), eq(List.of()), persisted.capture(), eq(List.of()));
        assertThat(persisted.getValue()).singleElement().satisfies(reference -> {
            assertThat(reference.articleId()).isEqualTo("TI-1");
            assertThat(reference.articleRevisionId()).isEqualTo("revision-1");
            assertThat(reference.evidenceSnapshotId()).isEqualTo("snapshot-1");
        });
    }

    @Test
    void streamBuffersSplitEvidenceTokensAndEmitsOnlyAuthorizedCitationProvenance() throws Exception {
        Fixture fixture = new Fixture();
        Fixture.configureGovernedEvidence(fixture);
        when(fixture.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                Fixture.modelResponse("Fact [[evidence:TI-1|revision-1|snapshot-1|"),
                Fixture.modelResponse("0-8]] then [[evidence:TI-1|wrong-revision|snapshot-1|0-8]] [FA"),
                Fixture.modelResponse("KE-1]")));

        List<ServerSentEvent<String>> events = fixture.service()
                .stream(new ChatRequest("known incident", null), "analyst-1")
                .collectList().block();

        ObjectMapper mapper = new ObjectMapper();
        StringBuilder answer = new StringBuilder();
        JsonNode done = null;
        for (ServerSentEvent<String> event : events) {
            JsonNode payload = mapper.readTree(event.data());
            if ("delta".equals(payload.path("type").asText())) {
                answer.append(payload.path("text").asText());
            } else if ("done".equals(payload.path("type").asText())) {
                done = payload;
            }
        }

        assertThat(answer.toString()).isEqualTo("Fact [TI-1] then  ");
        assertThat(answer).doesNotContain("[[evidence:").doesNotContain("wrong-revision").doesNotContain("FAKE-1");
        assertThat(done).isNotNull();
        assertThat(done.path("citations")).extracting(JsonNode::asText).containsExactly("TI-1");
        assertThat(done.path("citationReferences")).hasSize(1);
        assertThat(done.at("/citationReferences/0/articleRevisionId").asText()).isEqualTo("revision-1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvidenceReference>> persistedReferences = ArgumentCaptor.forClass(List.class);
        verify(fixture.conversations).appendAssistant(eq("analyst-1"), eq(fixture.conversation),
                eq("Fact [TI-1] then  "), eq(List.of("TI-1")), persistedReferences.capture(), eq(List.of()));
        assertThat(persistedReferences.getValue()).singleElement()
                .satisfies(reference -> {
                    assertThat(reference.articleId()).isEqualTo("TI-1");
                    assertThat(reference.articleRevisionId()).isEqualTo("revision-1");
                    assertThat(reference.evidenceSnapshotId()).isEqualTo("snapshot-1");
                    assertThat(reference.sourceSpanStart()).isZero();
                    assertThat(reference.sourceSpanEnd()).isEqualTo(8);
                });
    }

    @Test
    void streamNeverEmitsGroundedTextWhenFinalEvidenceBindingIsRevoked() throws Exception {
        Fixture fixture = new Fixture();
        Fixture.configureGovernedEvidence(fixture);
        when(fixture.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                Fixture.modelResponse("Sensitive remediation "
                        + "[[evidence:TI-1|revision-1|snapshot-1|0-8]]")));
        when(fixture.conversations.appendAssistant(anyString(), any(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<EvidenceReference> references = invocation.getArgument(4);
                    if (!references.isEmpty()) {
                        throw new IllegalArgumentException("evidence access revoked while streaming");
                    }
                    ChatMessage saved = new ChatMessage();
                    saved.setId("fallback-message");
                    saved.setCitations(List.of());
                    saved.setCitationReferences(List.of());
                    return saved;
                });

        List<ServerSentEvent<String>> events = fixture.service()
                .stream(new ChatRequest("known incident", null), "analyst-1")
                .collectList().block();

        ObjectMapper mapper = new ObjectMapper();
        String answer = events.stream().map(ServerSentEvent::data).map(data -> {
            try {
                return mapper.readTree(data);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }).filter(payload -> "delta".equals(payload.path("type").asText()))
                .map(payload -> payload.path("text").asText())
                .reduce("", String::concat);
        JsonNode done = mapper.readTree(events.get(events.size() - 1).data());

        assertThat(answer).isEqualTo(ABSTENTION).doesNotContain("Sensitive remediation");
        assertThat(done.path("citations")).isEmpty();
        assertThat(done.has("citationReferences")).isFalse();
    }

    @Test
    void streamSuppressesAnUploadAnswerWhenSourceAccessIsRevokedAfterRetrieval() throws Exception {
        Fixture fixture = new Fixture();
        RetrievedChunk upload = Fixture.privateUpload("source-owner");
        when(fixture.index.hybridSearch(eq("revoked upload"), eq(10), eq(0.30), eq("workspace-member"), any()))
                .thenReturn(List.of(upload));
        when(fixture.access.canReadSource("workspace-member", "upload-1", "upload-revision-1",
                "upload-1:upload-revision-1"))
                .thenReturn(allowedSource("SOURCE_WORKSPACE"), allowedSource("SOURCE_WORKSPACE"),
                        deniedSource("SOURCE_REVOKED"));
        Conversation conversation = Fixture.conversation("revoked-upload-conversation", "workspace-member");
        when(fixture.conversations.openForTurn("workspace-member", null, "revoked upload")).thenReturn(conversation);
        when(fixture.conversations.recentHistory("workspace-member", conversation.getId(), 4)).thenReturn(List.of());
        when(fixture.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                Fixture.modelResponse("Private upload secret must not be emitted.")));

        List<ServerSentEvent<String>> events = fixture.service()
                .stream(new ChatRequest("revoked upload", null), "workspace-member")
                .collectList().block();

        ObjectMapper mapper = new ObjectMapper();
        String answer = events.stream().map(ServerSentEvent::data).map(data -> {
            try {
                return mapper.readTree(data).path("text").asText("");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }).reduce("", String::concat);
        assertThat(answer).isEqualTo(ABSTENTION).doesNotContain("Private upload secret");
        verify(fixture.access, atLeastOnce()).canReadSource("workspace-member", "upload-1", "upload-revision-1",
                "upload-1:upload-revision-1");
    }

    @Test
    void authorizedPrivateUploadCanGroundWithoutArticleCitationOrProvenance() {
        Fixture fixture = new Fixture();
        RetrievedChunk upload = Fixture.privateUpload("analyst-1");
        when(fixture.index.hybridSearch(eq("uploaded incident"), eq(10), eq(0.30), eq("analyst-1"), any()))
                .thenReturn(List.of(upload));
        when(fixture.access.canReadSource("analyst-1", "upload-1", "upload-revision-1",
                "upload-1:upload-revision-1")).thenReturn(allowedSource("SOURCE_OWNER"));
        when(fixture.conversations.openForTurn("analyst-1", null, "uploaded incident"))
                .thenReturn(fixture.conversation);
        when(fixture.conversations.recentHistory("analyst-1", fixture.conversation.getId(), 4)).thenReturn(List.of());
        when(fixture.chatModel.call(any(Prompt.class))).thenReturn(Fixture.modelResponse(
                "{\"blocks\":[{\"type\":\"text\",\"text\":\"Rotate the uploaded credential. [FAKE-1]\"}],"
                        + "\"citations\":[\"FAKE-1\"]}"));

        ChatResponse response = fixture.service().chat(new ChatRequest("uploaded incident", null), "analyst-1");

        assertThat(response.blocks()).hasSize(1);
        assertThat(response.blocks().get(0).text())
                .contains("Rotate the uploaded credential.")
                .doesNotContain("FAKE-1");
        assertThat(response.citations()).isEmpty();
        assertThat(response.citationReferences()).isEmpty();
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(fixture.chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions())
                .extracting(Message::getText)
                .anySatisfy(text -> assertThat(text).contains("private upload evidence"));
        verify(fixture.access, atLeastOnce()).canReadSource("analyst-1", "upload-1", "upload-revision-1",
                "upload-1:upload-revision-1");
        verifyNoInteractions(fixture.articles);
    }

    @Test
    void policyAuthorizedPublicUploadCanGroundForANonOwner() {
        Fixture fixture = new Fixture();
        RetrievedChunk upload = Fixture.privateUpload("source-owner");
        Conversation conversation = Fixture.conversation("public-upload-conversation", "public-reader");
        when(fixture.index.hybridSearch(eq("public upload"), eq(10), eq(0.30), eq("public-reader"), any()))
                .thenReturn(List.of(upload));
        when(fixture.access.canReadSource("public-reader", "upload-1", "upload-revision-1",
                "upload-1:upload-revision-1")).thenReturn(allowedSource("SOURCE_PUBLIC"));
        when(fixture.conversations.openForTurn("public-reader", null, "public upload"))
                .thenReturn(conversation);
        when(fixture.conversations.recentHistory("public-reader", conversation.getId(), 4)).thenReturn(List.of());
        when(fixture.chatModel.call(any(Prompt.class))).thenReturn(Fixture.modelResponse(
                "{\"blocks\":[{\"type\":\"text\",\"text\":\"Rotate the uploaded credential.\"}],\"citations\":[]}"));

        ChatResponse response = fixture.service().chat(new ChatRequest("public upload", null), "public-reader");

        assertThat(response.blocks()).containsExactly(Block.text("Rotate the uploaded credential."));
        assertThat(response.citations()).isEmpty();
        assertThat(response.citationReferences()).isEmpty();
        verify(fixture.access, atLeastOnce()).canReadSource("public-reader", "upload-1", "upload-revision-1",
                "upload-1:upload-revision-1");
        verify(fixture.chatModel).call(any(Prompt.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SourceEvidenceReference>> sourceReferences = ArgumentCaptor.forClass(List.class);
        verify(fixture.conversations).appendAssistant(eq("public-reader"), eq(conversation),
                eq("Rotate the uploaded credential."), eq(List.of()), eq(List.of()), sourceReferences.capture());
        assertThat(sourceReferences.getValue()).containsExactly(new SourceEvidenceReference(
                "upload-1", "upload-revision-1", "upload-1:upload-revision-1"));
        verifyNoInteractions(fixture.articles);
    }

    @Test
    void policyAuthorizedWorkspaceUploadCanStreamForANonOwner() throws Exception {
        Fixture fixture = new Fixture();
        RetrievedChunk upload = Fixture.privateUpload("source-owner");
        Conversation conversation = Fixture.conversation("workspace-upload-conversation", "workspace-member");
        when(fixture.index.hybridSearch(eq("workspace upload"), eq(10), eq(0.30), eq("workspace-member"), any()))
                .thenReturn(List.of(upload));
        when(fixture.access.canReadSource("workspace-member", "upload-1", "upload-revision-1",
                "upload-1:upload-revision-1")).thenReturn(allowedSource("SOURCE_WORKSPACE"));
        when(fixture.conversations.openForTurn("workspace-member", null, "workspace upload"))
                .thenReturn(conversation);
        when(fixture.conversations.recentHistory("workspace-member", conversation.getId(), 4)).thenReturn(List.of());
        when(fixture.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                Fixture.modelResponse("Rotate the workspace upload.")));

        List<ServerSentEvent<String>> events = fixture.service()
                .stream(new ChatRequest("workspace upload", null), "workspace-member")
                .collectList().block();

        assertThat(events).isNotNull();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode done = mapper.readTree(events.get(events.size() - 1).data());
        assertThat(done.path("citations")).isEmpty();
        assertThat(done.has("citationReferences")).isFalse();
        verify(fixture.access, atLeastOnce()).canReadSource("workspace-member", "upload-1", "upload-revision-1",
                "upload-1:upload-revision-1");
        verify(fixture.chatModel).stream(any(Prompt.class));
        verifyNoInteractions(fixture.articles);
    }

    @Test
    void workspaceNonmemberCannotUseAnAuthorizedLookingUploadAndNoProviderIsCalled() {
        Fixture fixture = new Fixture();
        RetrievedChunk upload = Fixture.privateUpload("analyst-1");
        Conversation otherConversation = Fixture.conversation("other-conversation", "other-user");
        when(fixture.index.hybridSearch(eq("private upload"), eq(10), eq(0.30), eq("other-user"), any()))
                .thenReturn(List.of(upload));
        when(fixture.access.canReadSource("other-user", "upload-1", "upload-revision-1",
                "upload-1:upload-revision-1")).thenReturn(deniedSource("SOURCE_WORKSPACE_MEMBERSHIP_REQUIRED"));
        when(fixture.conversations.openForTurn("other-user", null, "private upload"))
                .thenReturn(otherConversation);
        ChatMessage prior = new ChatMessage();
        prior.setRole("user");
        prior.setContent("Prior context that would activate rewriting after evidence passes.");
        when(fixture.conversations.recentHistory("other-user", otherConversation.getId(), 4)).thenReturn(List.of(prior));

        ChatResponse response = fixture.service().chat(new ChatRequest("private upload", null), "other-user");

        assertThat(response.blocks()).containsExactly(Block.text(ABSTENTION));
        assertThat(response.citations()).isEmpty();
        assertThat(response.citationReferences()).isEmpty();
        verify(fixture.conversations).appendAssistant("other-user", otherConversation, ABSTENTION,
                List.of(), List.of(), List.of());
        verify(fixture.access).canReadSource("other-user", "upload-1", "upload-revision-1",
                "upload-1:upload-revision-1");
        verifyNoInteractions(fixture.chatModel, fixture.rewriterModel, fixture.rerankerModel, fixture.articles);
    }

    @Test
    void uploadWithoutCompleteServerStampedIdentityCannotUnlockAProviderCall() {
        Fixture fixture = new Fixture();
        RetrievedChunk incompleteUpload = new RetrievedChunk("private upload evidence", 0.9, Map.of(
                "source_id", "upload-1",
                "source_revision_id", "upload-revision-1",
                "owner_sub", "analyst-1"));
        when(fixture.index.hybridSearch(eq("incomplete upload"), eq(6), eq(0.30), eq("analyst-1"), any()))
                .thenReturn(List.of(incompleteUpload));
        when(fixture.conversations.openForTurn("analyst-1", null, "incomplete upload"))
                .thenReturn(fixture.conversation);

        ChatResponse response = fixture.service().chat(new ChatRequest("incomplete upload", null), "analyst-1");

        assertThat(response.blocks()).containsExactly(Block.text(ABSTENTION));
        assertThat(response.citations()).isEmpty();
        assertThat(response.citationReferences()).isEmpty();
        verifyNoInteractions(fixture.chatModel, fixture.rewriterModel, fixture.rerankerModel,
                fixture.access, fixture.articles);
    }

    @Test
    void ownerCanStreamPrivateUploadWithoutArticleCitationOrProvenance() throws Exception {
        Fixture fixture = new Fixture();
        RetrievedChunk upload = Fixture.privateUpload("analyst-1");
        when(fixture.index.hybridSearch(eq("stream uploaded incident"), eq(10), eq(0.30), eq("analyst-1"), any()))
                .thenReturn(List.of(upload));
        when(fixture.access.canReadSource("analyst-1", "upload-1", "upload-revision-1",
                "upload-1:upload-revision-1")).thenReturn(allowedSource("SOURCE_OWNER"));
        when(fixture.conversations.openForTurn("analyst-1", null, "stream uploaded incident"))
                .thenReturn(fixture.conversation);
        when(fixture.conversations.recentHistory("analyst-1", fixture.conversation.getId(), 4)).thenReturn(List.of());
        when(fixture.chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                Fixture.modelResponse("Rotate the uploaded credential. [FAKE-1]")));

        List<ServerSentEvent<String>> events = fixture.service()
                .stream(new ChatRequest("stream uploaded incident", null), "analyst-1")
                .collectList().block();

        ObjectMapper mapper = new ObjectMapper();
        StringBuilder answer = new StringBuilder();
        JsonNode done = null;
        for (ServerSentEvent<String> event : events) {
            JsonNode payload = mapper.readTree(event.data());
            if ("delta".equals(payload.path("type").asText())) {
                answer.append(payload.path("text").asText());
            } else if ("done".equals(payload.path("type").asText())) {
                done = payload;
            }
        }

        assertThat(answer).contains("Rotate the uploaded credential.").doesNotContain("FAKE-1");
        assertThat(done).isNotNull();
        assertThat(done.path("citations")).isEmpty();
        assertThat(done.has("citationReferences")).isFalse();
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(fixture.chatModel).stream(prompt.capture());
        assertThat(prompt.getValue().getInstructions())
                .extracting(Message::getText)
                .anySatisfy(text -> assertThat(text).contains("private upload evidence"));
        verify(fixture.access, atLeastOnce()).canReadSource("analyst-1", "upload-1", "upload-revision-1",
                "upload-1:upload-revision-1");
        verifyNoInteractions(fixture.articles);
    }

    @Test
    void rerankerInjectedUploadIsReauthorizedAndDeniedBeforeChat() {
        Fixture fixture = new Fixture();
        Fixture.configureGovernedEvidence(fixture);
        RetrievedChunk injected = Fixture.privateUpload("source-owner");
        Reranker reranker = mock(Reranker.class);
        when(reranker.isEnabled()).thenReturn(true);
        when(reranker.window()).thenReturn(10);
        when(reranker.rerank(eq("known incident"), any(), eq(6))).thenReturn(List.of(injected));
        when(fixture.access.canReadSource("analyst-1", "upload-1", "upload-revision-1",
                "upload-1:upload-revision-1")).thenReturn(deniedSource("SOURCE_NOT_READABLE"));

        ChatResponse response = fixture.service(reranker)
                .chat(new ChatRequest("known incident", null), "analyst-1");

        assertThat(response.blocks()).containsExactly(Block.text(ABSTENTION));
        verify(fixture.access).canReadSource("analyst-1", "upload-1", "upload-revision-1",
                "upload-1:upload-revision-1");
        verifyNoInteractions(fixture.chatModel, fixture.rewriterModel, fixture.rerankerModel);
    }

    @Test
    void iocLookupEvidenceBecomesAnExactCitationReference() {
        Fixture fixture = new Fixture();
        String question = "Have we seen 198.51.100.42?";
        EvidenceReference evidence = new EvidenceReference(
                "TI-IOC", "ioc-revision-1", 3, "ioc-snapshot-1", 0, 120,
                "https://example.test/ioc", "2026-09-06", "APPROVED");
        IocLookupService.IocHit hit = new IocLookupService.IocHit(
                "TI-IOC", "IOC report", "high", "8.1", "ip", "198.51.100.42", evidence);
        when(fixture.iocLookup.lookup(any(), eq("analyst-1"))).thenReturn(List.of(hit));
        when(fixture.index.hybridSearch(eq(question), eq(10), eq(0.30), eq("analyst-1"), any()))
                .thenReturn(List.of());
        Article article = new Article();
        article.setId("TI-IOC");
        article.setTitle("IOC report");
        when(fixture.access.readableArticleIds("analyst-1", Set.of("TI-IOC"))).thenReturn(Set.of("TI-IOC"));
        when(fixture.articles.findAllById(Set.of("TI-IOC"))).thenReturn(List.of(article));
        when(fixture.evidenceResolver.resolveCurrentApproved("analyst-1", evidence))
                .thenReturn(Optional.of(evidence));
        when(fixture.conversations.openForTurn("analyst-1", null, question)).thenReturn(fixture.conversation);
        when(fixture.conversations.recentHistory("analyst-1", fixture.conversation.getId(), 4)).thenReturn(List.of());
        String token = "[[evidence:TI-IOC|ioc-revision-1|ioc-snapshot-1|0-120]]";
        when(fixture.chatModel.call(any(Prompt.class))).thenReturn(Fixture.modelResponse(
                "{\"blocks\":[{\"type\":\"text\",\"text\":\"IOC matched " + token
                        + "\"}],\"citations\":[\"" + token + "\"]}"));

        ChatResponse response = fixture.service().chat(new ChatRequest(question, null), "analyst-1");

        assertThat(response.citations()).containsExactly("TI-IOC");
        assertThat(response.citationReferences()).containsExactly(evidence);
        verify(fixture.iocLookup).lookup(any(), eq("analyst-1"));
    }

    private static final class Fixture {
        final EmbeddingIndexService index = mock(EmbeddingIndexService.class);
        final ArticleRepository articles = mock(ArticleRepository.class);
        final KnowledgeAccessService access = mock(KnowledgeAccessService.class);
        final EvidenceReferenceResolver evidenceResolver = mock(EvidenceReferenceResolver.class);
        final ChatModel chatModel = mock(ChatModel.class);
        final ChatModel rewriterModel = mock(ChatModel.class);
        final ChatModel rerankerModel = mock(ChatModel.class);
        final ConversationService conversations = mock(ConversationService.class);
        final IocLookupService iocLookup = mock(IocLookupService.class);
        final OpenRouterModelCatalog modelCatalog = mock(OpenRouterModelCatalog.class);
        final Conversation conversation = conversation();

        Fixture() {
            when(conversations.openForTurn("analyst-1", null, "unknown incident")).thenReturn(conversation);
            when(conversations.appendAssistant(anyString(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
                ChatMessage saved = new ChatMessage();
                saved.setId("message-1");
                saved.setCitations(invocation.getArgument(3));
                saved.setCitationReferences(invocation.getArgument(4));
                saved.setSourceEvidenceReferences(invocation.getArgument(5));
                return saved;
            });
            ChatMessage previous = new ChatMessage();
            previous.setRole("user");
            previous.setContent("Earlier question that would otherwise invoke the rewriter");
            when(conversations.recentHistory("analyst-1", conversation.getId(), 4)).thenReturn(List.of(previous));
        }

        private static void configureGovernedEvidence(Fixture fixture) {
            RetrievedChunk chunk = new RetrievedChunk("source", 0.9, Map.of(
                    "article_id", "TI-1",
                    "article_revision_id", "revision-1",
                    "article_revision_number", 1,
                    "evidence_snapshot_id", "snapshot-1",
                    "source_span_start", 0,
                    "source_span_end", 8,
                    "source_uri", "https://example.test/source",
                    "source_version", "2026-09-05",
                    "revision_status", "APPROVED"));
            Article article = new Article();
            article.setId("TI-1");
            article.setTitle("Governed report");
            when(fixture.index.hybridSearch(eq("known incident"), eq(10), eq(0.30), eq("analyst-1"), any()))
                    .thenReturn(List.of(chunk));
            when(fixture.access.readableArticleIds("analyst-1", Set.of("TI-1"))).thenReturn(Set.of("TI-1"));
            when(fixture.articles.findAllById(Set.of("TI-1"))).thenReturn(List.of(article));
            EvidenceReference raw = new EvidenceReference(
                    "TI-1", "revision-1", 1, "snapshot-1", 0, 8,
                    "https://example.test/source", "2026-09-05", "APPROVED");
            EvidenceReference canonical = new EvidenceReference(
                    "TI-1", "revision-1", 1, "snapshot-1", 0, 8,
                    "https://example.test/source", "2026-09-05", "approved");
            when(fixture.evidenceResolver.resolveCurrentApproved(eq("analyst-1"), any()))
                    .thenReturn(Optional.of(canonical));
            when(fixture.conversations.openForTurn("analyst-1", null, "known incident"))
                    .thenReturn(fixture.conversation);
            when(fixture.conversations.recentHistory("analyst-1", fixture.conversation.getId(), 4)).thenReturn(List.of());
        }

        private static RetrievedChunk privateUpload(String ownerSub) {
            return new RetrievedChunk("private upload evidence: rotate the exposed credential", 0.9, Map.of(
                    "source", "private-upload",
                    "source_id", "upload-1",
                    "source_revision_id", "upload-revision-1",
                    "source_key", "upload-1:upload-revision-1",
                    "owner_sub", ownerSub));
        }

        private static org.springframework.ai.chat.model.ChatResponse modelResponse(String content) {
            return new org.springframework.ai.chat.model.ChatResponse(
                    List.of(new Generation(new AssistantMessage(content))));
        }

        RagService service() {
            return service(new Reranker(rerankerModel, true, 10));
        }

        RagService service(Reranker reranker) {
            RagService service = new RagService(index, articles, access, evidenceResolver, chatModel,
                    new PromptBuilder(9_000), new BlockParser(),
                    conversations, new IocExtractor(), iocLookup, modelCatalog,
                    new QueryRewriter(rewriterModel, true), reranker,
                    new SimpleMeterRegistry(), 6, 0.30, 4, "mistral");
            // Client wrappers may initialize eagerly; assertions cover provider-facing
            // interactions only after the request has entered RagService.
            clearInvocations(chatModel, rewriterModel, rerankerModel);
            return service;
        }

        private static Conversation conversation() {
            return conversation("conversation-1", "analyst-1");
        }

        private static Conversation conversation(String id, String userSub) {
            Conversation conversation = new Conversation();
            conversation.setId(id);
            conversation.setUserSub(userSub);
            return conversation;
        }
    }

    private static AccessDecision allowedSource(String reason) {
        return new AccessDecision(true, reason, "shared", "public", "approved");
    }

    private static AccessDecision deniedSource(String reason) {
        return new AccessDecision(false, reason, "shared", "internal", "approved");
    }
}
