package com.cyberguard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class QuizResultsResponse {
    private int score;
    private int total;
    private int percentage;
    private String timeTaken;
    private List<BreakdownItem> breakdown;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class BreakdownItem {
        private String question;
        private String yourAnswer;
        private String correctAnswer;
        private boolean correct;
        private String explanation;
    }
}
