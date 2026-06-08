package com.sentinel.kms.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the model's raw answer string into a {@code List<Block>} plus the set
 * of cited article ids. Robust against markdown code fences and surrounding
 * prose: it strips fences, extracts the first balanced {@code {...}} JSON
 * object, and Jackson-parses it into {@link Block}s via their static factories.
 *
 * <p>Citations are collected from the JSON {@code citations} array <em>and</em>
 * from any {@code [ID]} tokens found inside text/items, then filtered to the set
 * of ids actually present in retrieval. On any parse failure it falls back to a
 * single {@link Block#text} of the trimmed raw output, with citations scraped
 * from that raw text.
 */
@Component
public class BlockParser {

    /** Inline-citation token, e.g. {@code [TI-2291]}, {@code [CVE-2024-21413]}. */
    private static final Pattern CITE_TOKEN =
            Pattern.compile("\\[[A-Z0-9]+(?:-[A-Z0-9]+)*\\]");

    private static final Pattern CODE_FENCE =
            Pattern.compile("(?s)```(?:json)?\\s*(.*?)\\s*```");

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * The parse result: ordered blocks and the cited ids (already filtered to
     * valid ids).
     */
    public record Parsed(List<Block> blocks, List<String> citations) {
    }

    /**
     * @param raw     the model's raw response string
     * @param validIds the article ids that exist in retrieval; only citations in
     *                 this set are kept (if empty/null, no filtering is applied)
     */
    public Parsed parse(String raw, Set<String> validIds) {
        String rawTrimmed = raw == null ? "" : raw.trim();
        try {
            String json = extractJson(rawTrimmed);
            if (json == null) {
                return fallback(rawTrimmed, validIds);
            }
            JsonNode root = mapper.readTree(json);
            JsonNode blocksNode = root.get("blocks");
            if (blocksNode == null || !blocksNode.isArray()) {
                return fallback(rawTrimmed, validIds);
            }

            List<Block> blocks = new ArrayList<>();
            Set<String> cited = new LinkedHashSet<>();

            for (JsonNode b : blocksNode) {
                Block block = toBlock(b, cited);
                if (block != null) {
                    blocks.add(block);
                }
            }

            // Citations declared explicitly in the JSON.
            JsonNode citationsNode = root.get("citations");
            if (citationsNode != null && citationsNode.isArray()) {
                for (JsonNode c : citationsNode) {
                    String id = stripBrackets(c.asText());
                    if (id != null && !id.isBlank()) {
                        cited.add(id);
                    }
                }
            }

            if (blocks.isEmpty()) {
                return fallback(rawTrimmed, validIds);
            }

            return new Parsed(blocks, filterValid(cited, validIds));
        } catch (Exception e) {
            return fallback(rawTrimmed, validIds);
        }
    }

    /** Maps one JSON block node to a {@link Block}, harvesting inline citations. */
    private Block toBlock(JsonNode b, Set<String> cited) {
        if (b == null || !b.isObject()) {
            return null;
        }
        String type = text(b, "type");
        if (type == null) {
            return null;
        }
        switch (type) {
            case "text" -> {
                String t = text(b, "text");
                if (t == null) {
                    return null;
                }
                harvest(t, cited);
                return Block.text(t);
            }
            case "steps" -> {
                JsonNode items = b.get("items");
                if (items == null || !items.isArray()) {
                    return null;
                }
                List<String> list = new ArrayList<>();
                for (JsonNode it : items) {
                    String s = it.asText();
                    harvest(s, cited);
                    list.add(s);
                }
                return Block.steps(list);
            }
            case "callout" -> {
                String tone = text(b, "tone");
                String title = text(b, "title");
                String t = text(b, "text");
                // Normalize tone to the two supported values; default to "high".
                if (!"critical".equals(tone) && !"high".equals(tone)) {
                    tone = "high";
                }
                harvest(title, cited);
                harvest(t, cited);
                return Block.callout(tone, title, t);
            }
            case "report" -> {
                String title = text(b, "title");
                JsonNode rows = b.get("rows");
                List<List<String>> rowList = new ArrayList<>();
                if (rows != null && rows.isArray()) {
                    for (JsonNode row : rows) {
                        if (row.isArray()) {
                            List<String> cells = new ArrayList<>();
                            for (JsonNode cell : row) {
                                String c = cell.asText();
                                harvest(c, cited);
                                cells.add(c);
                            }
                            rowList.add(cells);
                        }
                    }
                }
                harvest(title, cited);
                return Block.report(title, rowList);
            }
            default -> {
                return null;
            }
        }
    }

    /** Extracts the first balanced JSON object, after stripping code fences. */
    private String extractJson(String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        String candidate = raw;
        Matcher fence = CODE_FENCE.matcher(raw);
        if (fence.find()) {
            candidate = fence.group(1).trim();
        }
        int start = candidate.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < candidate.length(); i++) {
            char ch = candidate.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (ch == '\\') {
                    escape = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return candidate.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /** Fallback path: one text block of the raw output, citations scraped from it. */
    private Parsed fallback(String rawTrimmed, Set<String> validIds) {
        Set<String> cited = new LinkedHashSet<>();
        harvest(rawTrimmed, cited);
        String text = rawTrimmed.isEmpty()
                ? "The assistant did not return a usable answer."
                : rawTrimmed;
        return new Parsed(List.of(Block.text(text)), filterValid(cited, validIds));
    }

    /** Adds every {@code [ID]} token found in {@code s} (without brackets) to {@code out}. */
    private void harvest(String s, Set<String> out) {
        if (s == null || s.isEmpty()) {
            return;
        }
        Matcher m = CITE_TOKEN.matcher(s);
        while (m.find()) {
            out.add(m.group().substring(1, m.group().length() - 1));
        }
    }

    private List<String> filterValid(Set<String> cited, Set<String> validIds) {
        if (validIds == null || validIds.isEmpty()) {
            return new ArrayList<>(cited);
        }
        List<String> kept = new ArrayList<>();
        for (String id : cited) {
            if (validIds.contains(id)) {
                kept.add(id);
            }
        }
        return kept;
    }

    private String stripBrackets(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.startsWith("[") && t.endsWith("]") && t.length() >= 2) {
            t = t.substring(1, t.length() - 1);
        }
        return t;
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
