package com.cyberguard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class QuizAnswerResponse {
    private boolean correct;
    private String explanation;
    private boolean isComplete;
    private QuizStartResponse.QuestionDto nextQuestion;
}
