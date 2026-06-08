package com.sentinel.kms.ingest;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

/**
 * Extracts plain text from arbitrary document bytes (PDF/DOCX/HTML/EML/...) via
 * Apache Tika. On any failure returns an empty string so the caller can mark the
 * owning job failed rather than aborting the pipeline.
 */
@Component
public class TextExtractor {

    /** Hard cap on extracted characters to keep chunking + embedding bounded. */
    private static final int MAX_CHARS = 200_000;

    private final Tika tika = createTika();

    private static Tika createTika() {
        Tika t = new Tika();
        t.setMaxStringLength(MAX_CHARS);
        return t;
    }

    /**
     * @param data     raw document bytes (may be {@code null})
     * @param filename original filename, used only as a Tika hint
     * @return extracted plain text, capped at ~200k chars, or "" on failure
     */
    public String extract(byte[] data, String filename) {
        if (data == null || data.length == 0) {
            return "";
        }
        try (ByteArrayInputStream in = new ByteArrayInputStream(data)) {
            Metadata metadata = new Metadata();
            if (filename != null && !filename.isBlank()) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
            }
            String text = tika.parseToString(in, metadata, MAX_CHARS);
            if (text == null) {
                return "";
            }
            return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        } catch (Exception e) {
            return "";
        }
    }
}
