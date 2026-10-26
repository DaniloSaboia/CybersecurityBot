package com.sentinel.kms.vectorstore;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RerankerTest {

    private static RetrievedChunk chunk(String content) {
        return new RetrievedChunk(content, 0.5, Map.of("article_id", "X"));
    }

    private static ChatModel modelReturning(String content) {
        ChatModel model = mock(ChatModel.class);
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
        when(model.call(any(Prompt.class))).thenReturn(response);
        return model;
    }

    private static ChatModel failingModel() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException("boom"));
        return model;
    }

    @Test
    void reordersCandidatesByModelScores() {
        ChatModel model = modelReturning("[{\"index\":2,\"score\":9},{\"index\":1,\"score\":1}]");
        Reranker reranker = new Reranker(model, true, 10);

        List<RetrievedChunk> out = reranker.rerank(
                "question", List.of(chunk("a"), chunk("b")), 2);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).content()).isEqualTo("b");
        assertThat(out.get(1).content()).isEqualTo("a");
    }

    @Test
    void cutsToTopKAfterScoring() {
        ChatModel model = modelReturning(
                "[{\"index\":3,\"score\":10},{\"index\":1,\"score\":5},{\"index\":2,\"score\":7}]");
        Reranker reranker = new Reranker(model, true, 10);

        List<RetrievedChunk> out = reranker.rerank(
                "q", List.of(chunk("a"), chunk("b"), chunk("c")), 2);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).content()).isEqualTo("c");
        assertThat(out.get(1).content()).isEqualTo("b");
    }

    @Test
    void failsOpenToOriginalOrderOnModelError() {
        Reranker reranker = new Reranker(failingModel(), true, 10);
        List<RetrievedChunk> out = reranker.rerank(
                "q", List.of(chunk("a"), chunk("b"), chunk("c")), 2);
        assertThat(out).extracting(RetrievedChunk::content).containsExactly("a", "b");
    }

    @Test
    void failsOpenOnUnparsableResponse() {
        ChatModel model = modelReturning("I cannot score these.");
        Reranker reranker = new Reranker(model, true, 10);
        List<RetrievedChunk> out = reranker.rerank(
                "q", List.of(chunk("a"), chunk("b")), 2);
        assertThat(out).extracting(RetrievedChunk::content).containsExactly("a", "b");
    }

    @Test
    void toleratesNoisyJsonWithProseAroundIt() {
        ChatModel model = modelReturning(
                "Here are the scores:\n```json\n[{\"index\":1,\"score\":3},{\"index\":2,\"score\":8}]\n```");
        Reranker reranker = new Reranker(model, true, 10);
        List<RetrievedChunk> out = reranker.rerank(
                "q", List.of(chunk("a"), chunk("b")), 2);
        assertThat(out.get(0).content()).isEqualTo("b");
    }

    @Test
    void disabledPassthroughLimitsOnly() {
        Reranker reranker = new Reranker(failingModel(), false, 10);
        List<RetrievedChunk> out = reranker.rerank(
                "q", List.of(chunk("a"), chunk("b"), chunk("c")), 1);
        assertThat(out).extracting(RetrievedChunk::content).containsExactly("a");
    }

    @Test
    void singleCandidateShortCircuitsWithoutModelCall() {
        ChatModel model = mock(ChatModel.class);
        Reranker reranker = new Reranker(model, true, 10);
        List<RetrievedChunk> out = reranker.rerank("q", List.of(chunk("only")), 5);
        assertThat(out).hasSize(1);
    }
}
