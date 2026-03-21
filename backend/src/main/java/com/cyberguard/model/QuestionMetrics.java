package com.cyberguard.model;

import lombok.Data;

@Data
public class QuestionMetrics {
    private String questionId;
    private int timesUsed;
    private int helpfulCount;
    private int unhelpfulCount;
    private double helpfulnessScore;

    public void incrementUsed() {
        timesUsed++;
    }

    public void incrementHelpful() {
        helpfulCount++;
        recalculate();
    }

    public void incrementUnhelpful() {
        unhelpfulCount++;
        recalculate();
    }

    private void recalculate() {
        int total = helpfulCount + unhelpfulCount;
        helpfulnessScore = total > 0 ? ((double) helpfulCount / total) * 100.0 : 0;
    }
}
