package com.cyberguard.dto;

import lombok.Data;

@Data
public class QuizStartRequest {
    private String category;
    private String difficulty;
}
