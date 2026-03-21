package com.cyberguard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LearningStatsResponse {
    private int totalFeedback;
    private double helpfulPercentage;
}
