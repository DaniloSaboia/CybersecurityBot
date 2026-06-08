package com.sentinel.kms.assistant;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Supplies the four static starter prompts for the Assistant view, matching
 * {@code CHAT_SUGGESTIONS} in the prototype's {@code data.jsx}.
 */
@Service
public class SuggestionService {

    private static final List<Suggestion> STARTERS = List.of(
            new Suggestion("triage", "alert",
                    "We got a device-code phishing alert — how do I triage?"),
            new Suggestion("summarize", "intel",
                    "Summarize this week's critical threat intel"),
            new Suggestion("playbook", "book",
                    "Pull the BEC containment playbook steps"),
            new Suggestion("report", "doc",
                    "Draft a remediation report for CVE-2024-21413")
    );

    /** The four starter prompts, in display order. */
    public List<Suggestion> starters() {
        return STARTERS;
    }
}
