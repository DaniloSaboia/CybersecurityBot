package com.sentinel.kms.kb;

import com.sentinel.kms.assistant.IocExtractor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Looks IOCs up in {@code articles.iocs} (jsonb array of {type, value}) and
 * returns the articles that mention them, so the assistant can answer
 * "have we seen 1.2.3.4?" from the KB's indicator index instead of relying
 * on embedding similarity alone.
 */
@Service
public class IocLookupService {

    /** One article × IOC match. */
    public record IocHit(String articleId, String title, String severity, String cvss,
                         String iocType, String iocValue) {
    }

    private final JdbcTemplate jdbc;

    public IocLookupService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param iocs extracted indicators (type is advisory; matching is by value)
     * @return one hit per (article, matching IOC value)
     */
    public List<IocHit> lookup(List<IocExtractor.Ioc> iocs) {
        List<IocHit> hits = new ArrayList<>();
        if (iocs == null || iocs.isEmpty()) {
            return hits;
        }
        List<String> values = iocs.stream().map(i -> i.value().toLowerCase()).distinct().toList();
        String placeholders = String.join(",", values.stream().map(v -> "?").toList());
        String sql = """
                select a.id, a.title, a.severity, a.cvss,
                       ioc->>'type' as ioc_type, ioc->>'value' as ioc_value
                from articles a, jsonb_array_elements(a.iocs) ioc
                where lower(ioc->>'value') in (%s)
                """.formatted(placeholders);
        jdbc.query(sql, (rs, n) -> {
            hits.add(new IocHit(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("severity"),
                    rs.getString("cvss"),
                    rs.getString("ioc_type"),
                    rs.getString("ioc_value")));
            return null;
        }, values.toArray());
        return hits;
    }
}
