package com.sentinel.kms.assistant;

/**
 * Request body for POST /api/assistant/context. Attaches a file/log/alert as
 * additional context for a subsequent chat query. Currently a stub: only a
 * {@code name} is accepted and a synthetic handle is returned (no real file
 * storage yet).
 *
 * @param name optional label of the attached artifact (filename, alert id, ...)
 */
public record ContextRequest(String name) {
}
