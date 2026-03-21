package com.cyberguard.model;

import lombok.Data;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
public class QuizSession {
    private List<QuizQuestion> questions;
    private int currentIndex;
    private int score;
    private List<QuizAnswer> answers = new ArrayList<>();
    private Instant startTime;
    private String category;

    @Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class QuizAnswer {
        private String questionId;
        private int selectedAnswer;
        private boolean correct;
    }
}
