package com.sentinel.kms.assistant;

/**
 * A starter prompt shown as a suggestion card in the Assistant view.
 *
 * @param id    stable id of the suggestion (triage/summarize/playbook/report)
 * @param icon  icon name the frontend renders (matches {@code icons.jsx})
 * @param label the prompt text sent as the user message when clicked
 */
public record Suggestion(String id, String icon, String label) {
}
