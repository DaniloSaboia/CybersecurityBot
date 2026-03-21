package com.cyberguard.controller;

import com.cyberguard.dto.LearningStatsResponse;
import com.cyberguard.dto.StatsResponse;
import com.cyberguard.service.LearningService;
import com.cyberguard.service.StorageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class StatsController {

    private final StorageService storageService;
    private final LearningService learningService;

    public StatsController(StorageService storageService, LearningService learningService) {
        this.storageService = storageService;
        this.learningService = learningService;
    }

    @GetMapping("/stats")
    public ResponseEntity<StatsResponse> stats() {
        return ResponseEntity.ok(new StatsResponse(
                storageService.getMessageCount(),
                storageService.getDuration(),
                storageService.getSentimentBreakdown()
        ));
    }

    @GetMapping("/learning-stats")
    public ResponseEntity<LearningStatsResponse> learningStats() {
        return ResponseEntity.ok(new LearningStatsResponse(
                learningService.getTotalFeedback(),
                learningService.getHelpfulPercentage()
        ));
    }
}
