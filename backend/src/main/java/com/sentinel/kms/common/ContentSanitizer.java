package com.sentinel.kms.common;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Neutralizes common prompt-injection directives in untrusted text before it is
 * stored, embedded, or placed into an LLM prompt. Ingested documents (uploads,
 * URL imports, connector feeds) are attacker-controllable, so obvious
 * "ignore previous instructions"-style payloads and fake role markers are
 * replaced with a visible marker instead of being passed through.
 *
 * <p>This is defense in depth: the primary defenses are (1) retrieved sources
 * are framed as untrusted data in the system prompt and (2) citations are
 * intersected with actually retrieved article ids, so injected content cannot
 * escalate by itself. The sanitizer keeps the obvious payloads out of prompts
 * entirely, and is deliberately conservative — only high-confidence directive
 * patterns are rewritten, so legitimate threat reports that quote phishing
 * lures keep their evidential value.
 */
public final class ContentSanitizer {

    private ContentSanitizer() {
    }

    private static final String MARKER = "[filtered-instruction]";

    /** Instruction-shaped payloads that try to hijack the assistant. */
    private static final List<Pattern> DIRECTIVES = List.of(
            // "ignore / disregard / forget (all) previous|prior|above instructions"
            Pattern.compile("(?i)\\b(ignore|disregard|forget)\\s+(all\\s+)?"
                    + "(previous|prior|above|earlier|preceding)\\s+(instructions?|prompts?|rules?|context)\\b"),
            // "you are now / act as / pretend to be <some other persona>"
            Pattern.compile("(?i)\\b(you\\s+are\\s+now|act\\s+as|pretend\\s+to\\s+be)\\s+(a\\s+|an\\s+)?"
                    + "(different|new|helpful|unrestricted|developer|dan)\\b"),
            // reveal-the-system-prompt family
            Pattern.compile("(?i)\\b(reveal|repeat|print|show)\\s+(your|the)\\s+"
                    + "(system\\s+)?(prompt|instructions)\\b"),
            // jailbreak tokens (<|im_start|>, </system>, [INST], etc.)
            Pattern.compile("(?i)<\\|?/?(system|im_start|im_end|endoftext)\\|?>|\\[/?(system|inst)\\]"));

    /** Fake chat role markers at the start of a line ("system:", "assistant:"). */
    private static final Pattern ROLE_MARKERS =
            Pattern.compile("(?im)^[ \\t>-]*(system|developer|assistant)\\s*:");

    /** The placeholder injected where a directive was removed. */
    public static final String PLACEHOLDER = MARKER;

    /**
     * Returns {@code text} with high-confidence injection payloads replaced by
     * {@link #PLACEHOLDER}. Null-safe; returns the input unchanged when blank.
     */
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (Pattern p : DIRECTIVES) {
            out = p.matcher(out).replaceAll(MARKER);
        }
        out = ROLE_MARKERS.matcher(out).replaceAll(MARKER);
        return out;
    }

    /** True when {@link #sanitize} would change the text (used in tests/logs). */
    public static boolean containsDirectives(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (Pattern p : DIRECTIVES) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return ROLE_MARKERS.matcher(text).find();
    }
}
