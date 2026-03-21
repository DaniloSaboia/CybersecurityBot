package com.cyberguard.controller;

import com.cyberguard.dto.*;
import com.cyberguard.service.QuizService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/quiz")
public class QuizController {

    private final QuizService quizService;

    public QuizController(QuizService quizService) {
        this.quizService = quizService;
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody QuizStartRequest request) {
        try {
            QuizStartResponse response = quizService.startQuiz(request.getCategory(), request.getDifficulty());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/answer")
    public ResponseEntity<?> answer(@RequestBody QuizAnswerRequest request) {
        try {
            QuizAnswerResponse response = quizService.submitAnswer(request.getAnswerIndex());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/results")
    public ResponseEntity<?> results() {
        QuizResultsResponse results = quizService.getResults();
        if (results == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No quiz session found"));
        }
        return ResponseEntity.ok(results);
    }

    @PostMapping("/end")
    public ResponseEntity<?> end() {
        quizService.endQuiz();
        return ResponseEntity.ok(Map.of("message", "Quiz ended"));
    }

    @GetMapping("/categories")
    public ResponseEntity<?> categories() {
        return ResponseEntity.ok(Map.of("categories", quizService.getCategories()));
    }
}
