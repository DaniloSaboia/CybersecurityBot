package com.sentinel.kms.kb;

/**
 * Wire DTO for a KB category chip. The API supplies the number of documents
 * readable by the current user, rather than the legacy stored headline count.
 */
public record CategoryDto(
        String id,
        String label,
        String abbr,
        int count
) {
    public static CategoryDto from(Category c) {
        return new CategoryDto(c.getId(), c.getLabel(), c.getAbbr(), c.getDocCount());
    }
}
