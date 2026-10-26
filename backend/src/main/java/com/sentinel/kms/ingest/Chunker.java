package com.sentinel.kms.ingest;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits text into overlapping character windows for embedding. Each window is
 * trimmed; empty windows are dropped.
 */
@Component
public class Chunker {

    /**
     * @param text    source text (may be {@code null}/blank)
     * @param size    window size in chars (clamped to >= 1)
     * @param overlap chars to re-include from the previous window (clamped to [0, size-1])
     * @return ordered list of non-empty, trimmed chunks
     */
    public List<String> chunk(String text, int size, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        int windowSize = Math.max(1, size);
        int safeOverlap = Math.max(0, Math.min(overlap, windowSize - 1));
        int step = windowSize - safeOverlap; // guaranteed >= 1

        int len = text.length();
        for (int start = 0; start < len; start += step) {
            int end = Math.min(start + windowSize, len);
            String piece = text.substring(start, end).trim();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
            if (end == len) {
                break;
            }
        }
        return chunks;
    }
}
