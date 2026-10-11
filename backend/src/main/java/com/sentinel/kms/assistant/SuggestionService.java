package com.sentinel.kms.assistant;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Practical incident starter prompts grounded by the bundled response playbooks.
 */
@Service
public class SuggestionService {

    private static final List<Suggestion> STARTERS = List.of(
            new Suggestion("triage", "alert",
                    "I clicked a suspicious email link. What should I do first?"),
            new Suggestion("summarize", "intel",
                    "Files are encrypted and there is a ransom note. How do we contain this safely?"),
            new Suggestion("playbook", "book",
                    "Someone accessed my work account. How do I regain control and check for damage?"),
            new Suggestion("report", "doc",
                    "An API key was exposed publicly. How do I revoke it and investigate misuse?")
    );

    /** The four starter prompts, in display order. */
    public List<Suggestion> starters() {
        return STARTERS;
    }
}
