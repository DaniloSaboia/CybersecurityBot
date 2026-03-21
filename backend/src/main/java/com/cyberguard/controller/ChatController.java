package com.cyberguard.controller;

import com.cyberguard.dto.ChatRequest;
import com.cyberguard.dto.ChatResponse;
import com.cyberguard.dto.FeedbackRequest;
import com.cyberguard.service.QuestionService;
import com.cyberguard.service.ResponseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api")
@Slf4j
public class ChatController {

    private final ResponseService responseService;
    private final QuestionService questionService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public ChatController(ResponseService responseService, QuestionService questionService) {
        this.responseService = responseService;
        this.questionService = questionService;
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message is required"));
        }
        String response = responseService.handleMessage(request.getMessage());
        return ResponseEntity.ok(new ChatResponse(response));
    }

    @PostMapping("/chat/stream")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(120_000L);

        if (request.getMessage() == null || request.getMessage().isBlank()) {
            executor.execute(() -> {
                try {
                    emitter.send("{\"error\":\"Message is required\"}", MediaType.APPLICATION_JSON);
                    emitter.complete();
                } catch (Exception e) { emitter.completeWithError(e); }
            });
            return emitter;
        }

        executor.execute(() -> {
            try {
                responseService.handleMessageStreaming(request.getMessage())
                    .doOnNext(chunk -> {
                        try {
                            emitter.send(SseEmitter.event().data(
                                Map.of("content", chunk),
                                MediaType.APPLICATION_JSON
                            ));
                        } catch (Exception e) {
                            emitter.completeWithError(e);
                        }
                    })
                    .doOnComplete(() -> {
                        try {
                            emitter.send(SseEmitter.event().data("[DONE]"));
                            emitter.complete();
                        } catch (Exception e) { emitter.completeWithError(e); }
                    })
                    .doOnError(emitter::completeWithError)
                    .blockLast();
            } catch (Exception e) {
                log.error("Stream error", e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @PostMapping("/feedback")
    public ResponseEntity<?> feedback(@RequestBody FeedbackRequest request) {
        if (request.getFeedback() == null ||
            (!"helpful".equals(request.getFeedback()) && !"unhelpful".equals(request.getFeedback()))) {
            return ResponseEntity.badRequest().body(Map.of("error", "Valid feedback is required"));
        }
        String response = responseService.provideFeedback(request.getFeedback());
        return ResponseEntity.ok(Map.of("response", response));
    }

    @GetMapping("/categories")
    public ResponseEntity<?> categories() {
        return ResponseEntity.ok(Map.of("categories", questionService.getAllCategories()));
    }
}
