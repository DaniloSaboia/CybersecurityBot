package com.sentinel.kms.assistant;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts indicators of compromise (IP, domain, hash, email) from free-form
 * analyst questions so {@link RagService} can look them up in the KB's IOC
 * index and ground the answer in the articles that mention them.
 *
 * <p>Deliberately conservative: a small TLD allowlist for domains (to avoid
 * flagging every dotted token) and exact-length hex matching for hashes.
 * Values are lowercased; duplicates are dropped. Capped at 8 indicators.
 */
@Component
public class IocExtractor {

    public record Ioc(String type, String value) {
    }

    private static final int MAX_IOCS = 8;

    private static final Pattern IPV4 =
            Pattern.compile("\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b");

    private static final Pattern SHA256 = Pattern.compile("\\b[a-fA-F0-9]{64}\\b");
    private static final Pattern SHA1 = Pattern.compile("\\b[a-fA-F0-9]{40}\\b");
    private static final Pattern MD5 = Pattern.compile("\\b[a-fA-F0-9]{32}\\b");

    private static final Pattern EMAIL =
            Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");

    private static final Pattern DOMAIN = Pattern.compile(
            "\\b(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+(?:com|net|org|io|ru|cn|xyz|top|info|biz|co|dev|app|me|tv|cc|us|uk|de|fr|br|in|au|ca|nl|se|es|it|pl|ua|ir|tk|ml|ga|cf|gq)\\b",
            Pattern.CASE_INSENSITIVE);

    /** All IOCs found in {@code text}, deduped, in first-seen order, capped. */
    public List<Ioc> extract(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<Ioc> found = new LinkedHashSet<>();
        // Remove emails first so their domain part isn't double-matched.
        Matcher emails = EMAIL.matcher(text);
        StringBuffer without = new StringBuffer();
        while (emails.find()) {
            found.add(new Ioc("email", emails.group().toLowerCase()));
            emails.appendReplacement(without, " ");
        }
        emails.appendTail(without);
        String rest = without.toString();

        Matcher ipv4 = IPV4.matcher(rest);
        while (ipv4.find() && found.size() < MAX_IOCS) {
            if (validOctets(ipv4)) {
                found.add(new Ioc("ip", ipv4.group()));
            }
        }
        collectHashes(rest, found);
        if (found.size() < MAX_IOCS) {
            Matcher domains = DOMAIN.matcher(rest);
            while (domains.find() && found.size() < MAX_IOCS) {
                found.add(new Ioc("domain", domains.group().toLowerCase()));
            }
        }
        return new ArrayList<>(found);
    }

    private void collectHashes(String text, Set<Ioc> found) {
        // Longest first so a sha256 isn't misread as md5/sha1.
        for (Pattern p : List.of(SHA256, SHA1, MD5)) {
            Matcher m = p.matcher(text);
            while (m.find() && found.size() < MAX_IOCS) {
                found.add(new Ioc(hashType(p), m.group().toLowerCase()));
            }
        }
    }

    private static String hashType(Pattern p) {
        if (p == SHA256) return "sha256";
        if (p == SHA1) return "sha1";
        return "md5";
    }

    private static boolean validOctets(Matcher m) {
        for (int g = 1; g <= 4; g++) {
            if (Integer.parseInt(m.group(g)) > 255) {
                return false;
            }
        }
        return true;
    }
}
