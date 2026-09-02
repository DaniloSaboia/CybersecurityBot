package com.sentinel.kms.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryRewriterTest {

    private static ChatModel modelReturning(String content) {
        ChatModel model = mock(ChatModel.class);
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
        when(model.call(any(Prompt.class))).thenReturn(response);
        return model;
    }

    private static final List<PromptBuilder.Turn> HISTORY = List.of(
            new PromptBuilder.Turn("user", "Tell me about CVE-2024-21413"),
            new PromptBuilder.Turn("assistant", "It's an Outlook RCE via Moniker Link..."));

    @Test
    void rewritesFollowUpUsingHistory() {
        QueryRewriter rewriter = new QueryRewriter(
                modelReturning("What is the CVSS of CVE-2024-21413?"), true);
        String out = rewriter.rewrite("what's its cvss?", HISTORY);
        assertThat(out).isEqualTo("What is the CVSS of CVE-2024-21413?");
    }

    @Test
    void stripsQuotesAndRewrittenPrefix() {
        QueryRewriter rewriter = new QueryRewriter(
                modelReturning("Rewritten question: \"Standalone form?\""), true);
        assertThat(rewriter.rewrite("q", HISTORY)).isEqualTo("Standalone form?");
    }

    @Test
    void noHistoryReturnsOriginal() {
        QueryRewriter rewriter = new QueryRewriter(modelReturning("never called"), true);
        assertThat(rewriter.rewrite("plain question", List.of())).isEqualTo("plain question");
    }

    @Test
    void disabledReturnsOriginal() {
        QueryRewriter rewriter = new QueryRewriter(modelReturning("never called"), false);
        assertThat(rewriter.rewrite("q", HISTORY)).isEqualTo("q");
    }

    @Test
    void modelFailureFallsBackToOriginal() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException("provider down"));
        QueryRewriter rewriter = new QueryRewriter(model, true);
        assertThat(rewriter.rewrite("original q", HISTORY)).isEqualTo("original q");
    }

    @Test
    void multilineOrRunawayAnswersAreRejected() {
        QueryRewriter rewriter = new QueryRewriter(modelReturning("line one\nline two"), true);
        assertThat(rewriter.rewrite("q", HISTORY)).isEqualTo("q");
    }
}
