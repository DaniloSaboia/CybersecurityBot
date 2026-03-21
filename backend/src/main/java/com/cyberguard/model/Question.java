package com.cyberguard.model;

import lombok.Data;
import java.util.List;

@Data
public class Question {
    private String id;
    private List<String> keywords;
    private String answer;
    private String category;
    private String difficulty;
}
