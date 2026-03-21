package com.cyberguard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class QuizStartResponse {
    private int totalQuestions;
    private QuestionDto currentQuestion;
    private int questionNumber;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class QuestionDto {
        private String question;
        private java.util.List<String> options;
        private String category;
        private String difficulty;
    }
}
