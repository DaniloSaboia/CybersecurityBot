package com.cyberguard.service;

import com.cyberguard.model.Question;
import com.cyberguard.model.QuestionMetrics;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class QuestionService {

    private List<Question> questions = new ArrayList<>();
    private final ObjectMapper objectMapper;
    private final LearningService learningService;

    public QuestionService(ObjectMapper objectMapper, LearningService learningService) {
        this.objectMapper = objectMapper;
        this.learningService = learningService;
    }

    @PostConstruct
    public void init() {
        try {
            InputStream is = new ClassPathResource("data/questions.json").getInputStream();
            questions = objectMapper.readValue(is, new TypeReference<List<Question>>() {});
            log.info("Loaded {} questions from knowledge base", questions.size());
        } catch (Exception e) {
            log.error("Failed to load questions.json", e);
        }
    }

    public record MatchResult(String answer, String questionId) {}

    public MatchResult findAnswer(String userQuery) {
        String normalized = userQuery.toLowerCase().trim();
        Question bestMatch = null;
        double bestScore = 0;

        for (Question q : questions) {
            if (q == null || q.getKeywords() == null) continue;

            double score = 0;
            for (String keyword : q.getKeywords()) {
                String kw = keyword.toLowerCase();
                if (normalized.contains(kw)) {
                    score += kw.split("\\s+").length * 2;
                }
                for (String word : kw.split("\\s+")) {
                    if (word.length() > 3 && normalized.contains(word)) {
                        score += 1;
                    }
                }
            }

            QuestionMetrics metrics = learningService.getMetrics(q.getId());
            if (metrics != null && metrics.getTimesUsed() > 0) {
                score += (metrics.getHelpfulnessScore() / 100.0) * 2;
            }

            if (score > bestScore) {
                bestScore = score;
                bestMatch = q;
            }
        }

        if (bestMatch != null) {
            return new MatchResult(bestMatch.getAnswer(), bestMatch.getId());
        }

        return new MatchResult(
            "I'm here to help with cybersecurity questions. Try asking about: passwords, phishing, encryption, malware, two-factor authentication, VPN, network security, or real-world incidents like WannaCry or the Equifax breach.",
            "unknown"
        );
    }

    public List<String> getAllCategories() {
        return questions.stream()
                .filter(q -> q != null && q.getCategory() != null)
                .map(Question::getCategory)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    public String getRandomTip() {
        List<String> tips = List.of(
            "Always use strong, unique passwords for each account.",
            "Enable two-factor authentication whenever possible.",
            "Keep your software and OS updated regularly.",
            "Be cautious of phishing emails and suspicious links.",
            "Use a VPN on public WiFi networks.",
            "Never share personal information via email.",
            "Use a password manager to store credentials securely.",
            "Monitor your accounts regularly for suspicious activity.",
            "Stay informed about recent data breaches and cyber incidents.",
            "Report phishing emails and suspicious activity to your provider.",
            "Backup your important data regularly to prevent loss.",
            "Check for HTTPS and the padlock before entering sensitive data."
        );
        return tips.get(new Random().nextInt(tips.size()));
    }
}
