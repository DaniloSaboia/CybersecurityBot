package com.cyberguard.service;

import com.cyberguard.model.QuestionMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class LearningService {

    private final ConcurrentHashMap<String, QuestionMetrics> metricsMap = new ConcurrentHashMap<>();

    public QuestionMetrics getMetrics(String questionId) {
        return metricsMap.get(questionId);
    }

    public void recordUsage(String questionId) {
        metricsMap.computeIfAbsent(questionId, id -> {
            QuestionMetrics m = new QuestionMetrics();
            m.setQuestionId(id);
            return m;
        }).incrementUsed();
    }

    public void recordFeedback(String questionId, boolean helpful) {
        QuestionMetrics m = metricsMap.computeIfAbsent(questionId, id -> {
            QuestionMetrics metrics = new QuestionMetrics();
            metrics.setQuestionId(id);
            return metrics;
        });
        if (helpful) {
            m.incrementHelpful();
        } else {
            m.incrementUnhelpful();
        }
        log.info("Feedback recorded for question {}: helpful={}", questionId, helpful);
    }

    public int getTotalFeedback() {
        return metricsMap.values().stream()
                .mapToInt(m -> m.getHelpfulCount() + m.getUnhelpfulCount())
                .sum();
    }

    public double getHelpfulPercentage() {
        int totalHelpful = metricsMap.values().stream().mapToInt(QuestionMetrics::getHelpfulCount).sum();
        int total = getTotalFeedback();
        return total > 0 ? ((double) totalHelpful / total) * 100.0 : 0;
    }
}
