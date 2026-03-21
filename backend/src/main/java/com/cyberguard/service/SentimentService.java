package com.cyberguard.service;

import org.springframework.stereotype.Service;
import java.util.Set;

@Service
public class SentimentService {

    private static final Set<String> POSITIVE_WORDS = Set.of(
        "thank", "thanks", "great", "good", "awesome", "helpful", "excellent",
        "amazing", "perfect", "wonderful", "appreciate", "love", "nice", "fantastic"
    );

    private static final Set<String> NEGATIVE_WORDS = Set.of(
        "bad", "terrible", "awful", "horrible", "useless", "wrong", "hate",
        "poor", "worst", "annoying", "frustrated", "confused", "disappointed"
    );

    public String analyzeSentiment(String text) {
        if (text == null || text.isBlank()) return "neutral";

        String lower = text.toLowerCase();
        long pos = POSITIVE_WORDS.stream().filter(lower::contains).count();
        long neg = NEGATIVE_WORDS.stream().filter(lower::contains).count();

        if (pos > neg) return "positive";
        if (neg > pos) return "negative";
        return "neutral";
    }
}
