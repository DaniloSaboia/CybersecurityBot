package com.sentinel.kms.kb;

/** Server-controlled producers permitted to initialize a public shared-feed policy. */
public enum TrustedArticleSource {
    SEED("system:seed", null),
    CURATED("system:knowledge-pack:sentinel-response-starter", "Sentinel response guidance"),
    NVD("system:connector:nvd", "NVD API"),
    KEV("system:connector:kev", "CISA KEV"),
    ATTACK("system:connector:attack", "MITRE ATT&CK"),
    SANS("system:connector:sans", "SANS ISC");

    private final String actor;
    private final String expectedSource;

    TrustedArticleSource(String actor, String expectedSource) {
        this.actor = actor;
        this.expectedSource = expectedSource;
    }

    public String actor() {
        return actor;
    }

    public void verify(Article article) {
        if (article == null || article.getId() == null || article.getId().isBlank()) {
            throw new IllegalArgumentException("trusted article id is required");
        }
        if (expectedSource != null && !expectedSource.equals(article.getSource())) {
            throw new IllegalArgumentException("trusted source does not match the server connector");
        }
        if (this == CURATED && !article.getId().matches("PB-START-[0-9]{3}")) {
            throw new IllegalArgumentException("curated guidance requires a reserved pack identity");
        }
    }
}
