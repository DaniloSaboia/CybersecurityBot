package com.sentinel.kms.kb;

/**
 * Wire DTO for a KB category chip. {@code count} maps to
 * {@link Category#getDocCount()} (the headline number shown on the chip).
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
