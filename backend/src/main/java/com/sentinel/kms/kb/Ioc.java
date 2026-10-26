package com.sentinel.kms.kb;

/**
 * Indicator of Compromise, stored inside {@link Article#getIocs()} as jsonb.
 * type ∈ {domain, ip, sha256, md5, email}.
 */
public record Ioc(String type, String value) {
}
