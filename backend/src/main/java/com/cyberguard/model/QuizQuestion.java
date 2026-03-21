package com.cyberguard.model;

import lombok.Data;
import java.util.List;

@Data
public class QuizQuestion {
    private String id;
    private String question;
    private List<String> options;
    private int correctAnswer;
    private String explanation;
    private String category;
    private String difficulty;
}
