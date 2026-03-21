package com.cyberguard.service;

import com.cyberguard.model.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Instant;

@Service
@Slf4j
public class ResponseService {

    private final QuestionService questionService;
    private final LlmService llmService;
    private final SentimentService sentimentService;
    private final LearningService learningService;
    private final StorageService storageService;

    private String lastAnsweredQuestionId;

    public ResponseService(QuestionService questionService,
                           LlmService llmService,
                           SentimentService sentimentService,
                           LearningService learningService,
                           StorageService storageService) {
        this.questionService = questionService;
        this.llmService = llmService;
        this.sentimentService = sentimentService;
        this.learningService = learningService;
        this.storageService = storageService;
    }

    public String handleMessage(String userMessage) {
        String sentiment = sentimentService.analyzeSentiment(userMessage);
        storageService.addMessage(new ChatMessage("user", userMessage, Instant.now(), sentiment, null));

        QuestionService.MatchResult match = questionService.findAnswer(userMessage);
        lastAnsweredQuestionId = match.questionId();
        boolean found = !"unknown".equals(match.questionId());

        if (found) {
            learningService.recordUsage(match.questionId());
            log.info("Found match in knowledge base (question {}), passing to LLM as context", match.questionId());
        } else {
            log.info("No match in knowledge base, LLM will answer from its own knowledge");
        }

        String response = llmService.generateResponse(userMessage, match.answer(), found);
        storageService.addMessage(new ChatMessage("bot", response, Instant.now(), null, null));
        return response;
    }

    public Flux<String> handleMessageStreaming(String userMessage) {
        String sentiment = sentimentService.analyzeSentiment(userMessage);
        storageService.addMessage(new ChatMessage("user", userMessage, Instant.now(), sentiment, null));

        QuestionService.MatchResult match = questionService.findAnswer(userMessage);
        lastAnsweredQuestionId = match.questionId();
        boolean found = !"unknown".equals(match.questionId());

        if (found) {
            learningService.recordUsage(match.questionId());
            log.info("Found match in knowledge base (question {}), passing to LLM as context", match.questionId());
        } else {
            log.info("No match in knowledge base, LLM will answer from its own knowledge");
        }

        StringBuilder full = new StringBuilder();
        return llmService.generateStreamingResponse(userMessage, match.answer(), found)
                .doOnNext(full::append)
                .doOnComplete(() ->
                    storageService.addMessage(new ChatMessage("bot", full.toString(), Instant.now(), null, null))
                );
    }

    public String provideFeedback(String feedback) {
        if (lastAnsweredQuestionId != null && !"unknown".equals(lastAnsweredQuestionId)) {
            learningService.recordFeedback(lastAnsweredQuestionId, "helpful".equals(feedback));
        }
        return "helpful".equals(feedback)
                ? "Thank you for your positive feedback!"
                : "Thank you for your feedback. I'll try to improve.";
    }

    public String getLastAnsweredQuestionId() {
        return lastAnsweredQuestionId;
    }
}
