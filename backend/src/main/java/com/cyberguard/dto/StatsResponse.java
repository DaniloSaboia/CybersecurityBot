package com.cyberguard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class StatsResponse {
    private int messageCount;
    private String duration;
    private Map<String, Integer> sentimentBreakdown;
}
