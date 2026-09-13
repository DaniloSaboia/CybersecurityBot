package com.sentinel.kms.assistant;

import com.sentinel.kms.common.EvidenceReference;
import com.sentinel.kms.vectorstore.RetrievedChunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves model-provided citation syntax only against evidence returned by a
 * governed retrieval. A token is never an authorization decision by itself.
 *
 * <p>The internal syntax is intentionally exact and non-public:
 * {@code [[evidence:articleId|articleRevisionId|evidenceSnapshotId|start-end]]}.
 * Valid internal tokens are rendered as legacy {@code [ARTICLE-ID]} tokens so
 * existing clients continue to receive their {@code citations: string[]} contract.
 */
final class CitationResolver {

    private static final Pattern LEGACY_TOKEN =
            Pattern.compile("\\[[A-Z0-9]+(?:-[A-Z0-9]+)*\\]");
    private static final Pattern INTERNAL_TOKEN = Pattern.compile(
            "\\[\\[evidence:([^|\\[\\]\\r\\n]+)\\|([^|\\[\\]\\r\\n]+)"
                    + "\\|([^|\\[\\]\\r\\n]+)\\|([0-9]+)-([0-9]+)\\]\\]");
    private static final Pattern EVIDENCE_LIKE_TOKEN =
            Pattern.compile("\\[\\[evidence:[^\\r\\n]{0,512}?\\]\\]");
    private static final int MAX_PENDING_TOKEN_CHARS = 512;

    private final Set<String> allowedArticleIds;
    private final Map<String, EvidenceReference> evidenceByToken;
    /** Legacy article-id syntax is a compatibility parser only, never a production proof of evidence. */
    private final boolean acceptLegacyArticleIds;

    private CitationResolver(Set<String> allowedArticleIds, Map<String, EvidenceReference> evidenceByToken,
                             boolean acceptLegacyArticleIds) {
        this.allowedArticleIds = Set.copyOf(allowedArticleIds);
        this.evidenceByToken = Map.copyOf(evidenceByToken);
        this.acceptLegacyArticleIds = acceptLegacyArticleIds;
    }

    static CitationResolver legacyOnly(Set<String> allowedArticleIds) {
        return new CitationResolver(normalizeIds(allowedArticleIds), Map.of(), true);
    }

    /** Builds a resolver only from chunks that already passed authorization. */
    static CitationResolver fromAuthorizedChunks(List<RetrievedChunk> chunks, Set<String> allowedArticleIds) {
        Set<String> allowed = normalizeIds(allowedArticleIds);
        Map<String, EvidenceReference> evidence = new LinkedHashMap<>();
        if (chunks != null) {
            for (RetrievedChunk chunk : chunks) {
                if (chunk == null || chunk.articleId() == null || !allowed.contains(chunk.articleId())) {
                    continue;
                }
                EvidenceReference.fromMetadata(chunk.metadata()).ifPresent(reference -> {
                    // The metadata identity must agree with the article that was authorized.
                    if (!allowed.contains(reference.articleId()) || !reference.articleId().equals(chunk.articleId())
                            || reference.sourceSpanStart() == null || reference.sourceSpanEnd() == null) {
                        return;
                    }
                    evidence.putIfAbsent(evidenceToken(reference), reference);
                });
            }
        }
        return new CitationResolver(allowed, evidence, true);
    }

    /**
     * Builds a token registry only where a retrieved chunk's immutable identity matches an
     * independently server-resolved current evidence record. The raw vector metadata is used
     * solely to locate the matching chunk; URI, version, and status exposed to callers come
     * from {@code serverBoundEvidence}, never from that metadata.
     */
    static CitationResolver fromServerBoundEvidence(List<RetrievedChunk> chunks,
                                                     List<EvidenceReference> serverBoundEvidence,
                                                     Set<String> allowedArticleIds) {
        Set<String> allowed = normalizeIds(allowedArticleIds);
        Map<EvidenceIdentity, EvidenceReference> canonicalByIdentity = new LinkedHashMap<>();
        if (serverBoundEvidence != null) {
            for (EvidenceReference reference : serverBoundEvidence) {
                EvidenceIdentity identity = EvidenceIdentity.from(reference);
                if (identity != null && allowed.contains(reference.articleId())) {
                    canonicalByIdentity.putIfAbsent(identity, reference);
                }
            }
        }

        Map<String, EvidenceReference> evidence = new LinkedHashMap<>();
        if (chunks != null) {
            for (RetrievedChunk chunk : chunks) {
                if (chunk == null || chunk.articleId() == null || !allowed.contains(chunk.articleId())) {
                    continue;
                }
                EvidenceReference.fromMetadata(chunk.metadata()).ifPresent(rawReference -> {
                    EvidenceReference canonical = canonicalByIdentity.get(EvidenceIdentity.from(rawReference));
                    if (canonical != null && canonical.articleId().equals(chunk.articleId())) {
                        evidence.putIfAbsent(evidenceToken(canonical), canonical);
                    }
                });
            }
        }
        // Request paths use this exact-evidence registry. A bare article id is
        // display syntax, not provenance, and therefore cannot become a citation.
        return new CitationResolver(allowed, evidence, false);
    }

    boolean hasAuthorizedArticleEvidence() {
        return !allowedArticleIds.isEmpty();
    }

    String evidenceTokenFor(RetrievedChunk chunk) {
        if (chunk == null || chunk.articleId() == null || !allowedArticleIds.contains(chunk.articleId())) {
            return null;
        }
        return EvidenceReference.fromMetadata(chunk.metadata())
                .map(CitationResolver::evidenceToken)
                .filter(evidenceByToken::containsKey)
                .orElse(null);
    }

    /** Exact canonical evidence that was matched to the governed retrieval context. */
    List<EvidenceReference> authorizedReferences() {
        return List.copyOf(evidenceByToken.values());
    }

    Citations newCitations() {
        return new Citations();
    }

    /** Converts only verified internal tokens and removes unknown citation-shaped tokens. */
    String sanitize(String value, Citations citations) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return replaceInternalTokens(value, citations);
    }

    /**
     * Processes source text in segments so a verified internal token can render as a legacy
     * article id without that rendered display text being mistaken for an unverified raw token.
     */
    private String replaceInternalTokens(String value, Citations citations) {
        Matcher internal = INTERNAL_TOKEN.matcher(value);
        StringBuffer safe = new StringBuffer();
        int cursor = 0;
        while (internal.find()) {
            safe.append(sanitizeLegacySegment(value.substring(cursor, internal.start()), citations));
            String token = internal.group();
            EvidenceReference reference = evidenceByToken.get(token);
            if (reference != null) {
                citations.addEvidence(reference);
                safe.append("[").append(reference.articleId()).append("]");
            }
            cursor = internal.end();
        }
        safe.append(sanitizeLegacySegment(value.substring(cursor), citations));
        return safe.toString();
    }

    private String sanitizeLegacySegment(String value, Citations citations) {
        String noUnknownEvidence = EVIDENCE_LIKE_TOKEN.matcher(value).replaceAll("");
        Matcher legacy = LEGACY_TOKEN.matcher(noUnknownEvidence);
        StringBuffer safe = new StringBuffer();
        while (legacy.find()) {
            String token = legacy.group();
            String id = token.substring(1, token.length() - 1);
            String replacement = acceptLegacyArticleIds && allowedArticleIds.contains(id) ? token : "";
            if (!replacement.isEmpty()) {
                citations.addLegacy(id);
            }
            legacy.appendReplacement(safe, Matcher.quoteReplacement(replacement));
        }
        legacy.appendTail(safe);
        return safe.toString();
    }

    void collectDeclaredCitation(String value, Citations citations) {
        if (value == null || value.isBlank()) {
            return;
        }
        String token = value.trim();
        EvidenceReference reference = evidenceByToken.get(token);
        if (reference != null) {
            citations.addEvidence(reference);
            return;
        }
        if (token.startsWith("[") && token.endsWith("]") && token.length() >= 2) {
            token = token.substring(1, token.length() - 1);
        }
        if (acceptLegacyArticleIds && allowedArticleIds.contains(token)) {
            citations.addLegacy(token);
        }
    }

    StreamingSanitizer streamingSanitizer() {
        return new StreamingSanitizer();
    }

    private String sanitizeCompleteToken(String token, Citations citations) {
        EvidenceReference reference = evidenceByToken.get(token);
        if (reference != null) {
            citations.addEvidence(reference);
            return "[" + reference.articleId() + "]";
        }
        if (token.startsWith("[[evidence:")) {
            return "";
        }
        Matcher legacy = LEGACY_TOKEN.matcher(token);
        if (legacy.matches()) {
            String id = token.substring(1, token.length() - 1);
            if (acceptLegacyArticleIds && allowedArticleIds.contains(id)) {
                citations.addLegacy(id);
                return token;
            }
            return "";
        }
        return token;
    }

    private static String evidenceToken(EvidenceReference reference) {
        return "[[evidence:" + reference.articleId() + "|" + reference.articleRevisionId()
                + "|" + reference.evidenceSnapshotId() + "|" + reference.sourceSpanStart()
                + "-" + reference.sourceSpanEnd() + "]]";
    }

    private static Set<String> normalizeIds(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                out.add(id);
            }
        }
        return out;
    }

    /** Core immutable fields that select one evidence span; display provenance is deliberately excluded. */
    private record EvidenceIdentity(String articleId, String articleRevisionId, int revisionNumber,
                                    String evidenceSnapshotId, int sourceSpanStart, int sourceSpanEnd) {
        static EvidenceIdentity from(EvidenceReference reference) {
            if (reference == null || blank(reference.articleId()) || blank(reference.articleRevisionId())
                    || blank(reference.evidenceSnapshotId()) || reference.revisionNumber() < 1
                    || reference.sourceSpanStart() == null || reference.sourceSpanEnd() == null
                    || reference.sourceSpanStart() < 0 || reference.sourceSpanEnd() < reference.sourceSpanStart()) {
                return null;
            }
            return new EvidenceIdentity(reference.articleId(), reference.articleRevisionId(),
                    reference.revisionNumber(), reference.evidenceSnapshotId(),
                    reference.sourceSpanStart(), reference.sourceSpanEnd());
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** Ordered, de-duplicated citations accumulated while one answer is rendered. */
    static final class Citations {
        private final Set<String> legacyIds = new LinkedHashSet<>();
        private final Map<String, EvidenceReference> references = new LinkedHashMap<>();

        void addLegacy(String articleId) {
            if (articleId != null && !articleId.isBlank()) {
                legacyIds.add(articleId);
            }
        }

        void addEvidence(EvidenceReference reference) {
            if (reference == null) {
                return;
            }
            addLegacy(reference.articleId());
            references.putIfAbsent(evidenceToken(reference), reference);
        }

        List<String> legacyIds() {
            return List.copyOf(legacyIds);
        }

        List<EvidenceReference> references() {
            return List.copyOf(references.values());
        }
    }

    /** Stateful stream adapter that never emits a citation token before it is validated. */
    final class StreamingSanitizer {
        private final StringBuilder pending = new StringBuilder();
        private final Citations citations = newCitations();
        private boolean discardingOverlongEvidence;
        private boolean discardedClosingBracket;

        String accept(String delta) {
            if (delta == null || delta.isEmpty()) {
                return "";
            }
            StringBuilder out = new StringBuilder(delta.length());
            for (int i = 0; i < delta.length(); i++) {
                char ch = delta.charAt(i);
                if (discardingOverlongEvidence) {
                    if (ch == ']' && discardedClosingBracket) {
                        discardingOverlongEvidence = false;
                        discardedClosingBracket = false;
                    } else {
                        discardedClosingBracket = ch == ']';
                    }
                    continue;
                }
                if (pending.isEmpty()) {
                    if (ch == '[') {
                        pending.append(ch);
                    } else {
                        out.append(ch);
                    }
                    continue;
                }

                if (ch == '[' && !(pending.length() == 1 && pending.charAt(0) == '[')) {
                    out.append(pending);
                    pending.setLength(0);
                    pending.append(ch);
                    continue;
                }

                pending.append(ch);
                boolean internal = pending.length() >= 2
                        && pending.charAt(0) == '[' && pending.charAt(1) == '[';
                boolean complete = internal
                        ? pending.length() >= 2 && pending.charAt(pending.length() - 1) == ']'
                        && pending.charAt(pending.length() - 2) == ']'
                        : ch == ']';
                if (complete) {
                    out.append(sanitizeCompleteToken(pending.toString(), citations));
                    pending.setLength(0);
                } else if (pending.length() >= MAX_PENDING_TOKEN_CHARS) {
                    if (internal && pending.toString().startsWith("[[evidence:")) {
                        // Do not let a malformed or adversarially oversized token escape the
                        // stream as raw text. Suppress through its closing delimiter (if any).
                        discardingOverlongEvidence = true;
                        discardedClosingBracket = false;
                    } else {
                        out.append(pending);
                    }
                    pending.setLength(0);
                }
            }
            return out.toString();
        }

        String finish() {
            pending.setLength(0);
            discardingOverlongEvidence = false;
            discardedClosingBracket = false;
            // `pending` is always citation-shaped (it starts with '['). Never
            // leak an unterminated, unverified token at the end of an SSE stream.
            return "";
        }

        Citations citations() {
            return citations;
        }
    }
}
