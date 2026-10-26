package com.sentinel.kms.assistant;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One renderable block of an assistant answer. The frontend understands four
 * {@code type}s; only the fields relevant to a type are populated (nulls are
 * omitted from JSON), matching the prototype's response contract:
 *
 * <ul>
 *   <li>text    → {@code text}</li>
 *   <li>steps   → {@code items}</li>
 *   <li>callout → {@code tone} (critical|high), {@code title}, {@code text}</li>
 *   <li>report  → {@code title}, {@code rows} (array of [label, value] pairs)</li>
 * </ul>
 *
 * Inline citations use the {@code [ARTICLE-ID]} token inside text/items.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Block(
        String type,
        String text,
        List<String> items,
        String tone,
        String title,
        List<List<String>> rows
) {
    public static Block text(String text) {
        return new Block("text", text, null, null, null, null);
    }

    public static Block steps(List<String> items) {
        return new Block("steps", null, items, null, null, null);
    }

    public static Block callout(String tone, String title, String text) {
        return new Block("callout", text, null, tone, title, null);
    }

    public static Block report(String title, List<List<String>> rows) {
        return new Block("report", null, null, null, title, rows);
    }
}
