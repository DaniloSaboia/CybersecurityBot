package com.sentinel.kms.vectorstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring AI implementation of {@link EmbeddingIndexService}. Indexing calls the
 * active {@code EmbeddingModel} (via {@code VectorStore.add}) and stores vectors
 * in the {@code vector_store} pgvector table; {@link #search} runs cosine
 * similarity, while {@link #hybridSearch} fuses that vector leg with a Postgres
 * full-text leg over the generated {@code content_tsv} column (see the upgrade
 * migration) via reciprocal-rank fusion.
 */
@Service
public class SpringAiEmbeddingIndexService implements EmbeddingIndexService {

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpringAiEmbeddingIndexService(
            VectorStore vectorStore,
            JdbcTemplate jdbcTemplate,
            EmbeddingModel embeddingModel) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public int index(List<String> chunks, Map<String, Object> metadata) {
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }
        List<Document> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            String text = chunks.get(i);
            if (text == null || text.isBlank()) {
                continue;
            }
            Map<String, Object> md = new HashMap<>();
            if (metadata != null) {
                md.putAll(metadata);
            }
            md.put("chunk_index", i);
            docs.add(Document.builder().text(text).metadata(md).build());
        }
        if (docs.isEmpty()) {
            return 0;
        }
        vectorStore.add(docs);
        return docs.size();
    }

    @Override
    public List<RetrievedChunk> search(
            String query, int topK, double minScore, String userSub) {
        return hybridSearch(query, topK, minScore, userSub);
    }

    @Override
    public List<RetrievedChunk> hybridSearch(
            String query, int topK, double minScore, String userSub) {
        return hybridSearch(query, topK, minScore, userSub, null);
    }

    @Override
    public List<RetrievedChunk> hybridSearch(
            String query, int topK, double minScore, String userSub, RetrievalFilters filters) {
        boolean filtered = filters != null && !filters.isEmpty();
        String category = filtered ? filters.category() : null;
        Integer minSeverityRank = filtered
                && filters.minSeverity() != null && !filters.minSeverity().isBlank()
                ? RetrievalFilters.severityRank(filters.minSeverity()) : null;

        List<HybridRanker.LegRow> vectorLeg;
        try {
            float[] vector = embeddingModel.embed(query);
            String literal = toVectorLiteral(vector);
            vectorLeg = jdbcTemplate.query(filtered ? """
                            select vs.id::text, vs.content, vs.metadata::text as metadata,
                                   1 - (vs.embedding OPERATOR(extensions.<=>)
                                        ?::extensions.vector) as score
                            from vector_store vs
                            left join articles a on a.id = vs.metadata->>'article_id'
                            where 1 - (vs.embedding OPERATOR(extensions.<=>)
                                       ?::extensions.vector) >= ?
                              and (
                                  vs.metadata ? 'article_id'
                                  or exists (
                                      select 1
                                      from ingest_jobs ij
                                      where ij.id = vs.metadata->>'job_id'
                                        and (ij.owner_sub = ? or ij.owner_sub is null)
                                  )
                              )
                              and (cast(? as text) is null or a.category_id = cast(? as text))
                              and (cast(? as int) is null
                                   or (case a.severity when 'critical' then 4 when 'high' then 3
                                        when 'medium' then 2 when 'low' then 1 else 0 end)
                                       >= cast(? as int))
                            order by vs.embedding OPERATOR(extensions.<=>)
                                     ?::extensions.vector
                            limit ?"""
                            : """
                            select vs.id::text, vs.content, vs.metadata::text as metadata,
                                   1 - (vs.embedding OPERATOR(extensions.<=>)
                                        ?::extensions.vector) as score
                            from vector_store vs
                            where 1 - (vs.embedding OPERATOR(extensions.<=>)
                                       ?::extensions.vector) >= ?
                              and (
                                  vs.metadata ? 'article_id'
                                  or exists (
                                      select 1
                                      from ingest_jobs ij
                                      where ij.id = vs.metadata->>'job_id'
                                        and (ij.owner_sub = ? or ij.owner_sub is null)
                                  )
                              )
                            order by vs.embedding OPERATOR(extensions.<=>)
                                     ?::extensions.vector
                            limit ?""",
                    (rs, n) -> new HybridRanker.LegRow(
                            rs.getString("id"), rs.getString("content"),
                            rs.getString("metadata"), rs.getDouble("score"), false),
                    filtered
                            ? new Object[]{literal, literal, minScore, userSub,
                                category, category, minSeverityRank, minSeverityRank, literal, topK * 2}
                            : new Object[]{literal, literal, minScore, userSub, literal, topK * 2});
        } catch (Exception e) {
            // Embedding or DB hiccup: degrade to the lexical leg only.
            vectorLeg = List.of();
        }

        List<HybridRanker.LegRow> ftsLeg = List.of();
        try {
            ftsLeg = jdbcTemplate.query(filtered ? """
                            select vs.id::text, vs.content, vs.metadata::text as metadata,
                                   ts_rank(vs.content_tsv, q) as score
                            from vector_store vs, websearch_to_tsquery('simple', ?) q
                            left join articles a on a.id = vs.metadata->>'article_id'
                            where vs.content_tsv @@ q
                              and (
                                  vs.metadata ? 'article_id'
                                  or exists (
                                      select 1
                                      from ingest_jobs ij
                                      where ij.id = vs.metadata->>'job_id'
                                        and (ij.owner_sub = ? or ij.owner_sub is null)
                                  )
                              )
                              and (cast(? as text) is null or a.category_id = cast(? as text))
                              and (cast(? as int) is null
                                   or (case a.severity when 'critical' then 4 when 'high' then 3
                                        when 'medium' then 2 when 'low' then 1 else 0 end)
                                       >= cast(? as int))
                            order by ts_rank(vs.content_tsv, q) desc
                            limit ?"""
                            : """
                            select vs.id::text, vs.content, vs.metadata::text as metadata,
                                   ts_rank(vs.content_tsv, q) as score
                            from vector_store vs, websearch_to_tsquery('simple', ?) q
                            where vs.content_tsv @@ q
                              and (
                                  vs.metadata ? 'article_id'
                                  or exists (
                                      select 1
                                      from ingest_jobs ij
                                      where ij.id = vs.metadata->>'job_id'
                                        and (ij.owner_sub = ? or ij.owner_sub is null)
                                  )
                              )
                            order by ts_rank(vs.content_tsv, q) desc
                            limit ?""",
                    (rs, n) -> new HybridRanker.LegRow(
                            rs.getString("id"), rs.getString("content"),
                            rs.getString("metadata"), rs.getDouble("score"), true),
                    filtered
                            ? new Object[]{query, userSub, category, category,
                                minSeverityRank, minSeverityRank, topK * 2}
                            : new Object[]{query, userSub, topK * 2});
        } catch (Exception ignored) {
            // No FTS index / empty table — vector leg still answers.
        }

        List<RetrievedChunk> out = new ArrayList<>();
        for (HybridRanker.LegRow row : HybridRanker.merge(vectorLeg, ftsLeg, topK)) {
            out.add(new RetrievedChunk(row.content(), row.score(), parseMetadata(row.metadataJson())));
        }
        return out;
    }

    @Override
    public long count() {
        Long n = jdbcTemplate.queryForObject("select count(*) from vector_store", Long.class);
        return n == null ? 0L : n;
    }

    @Override
    public int deleteByJob(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return 0;
        }
        // metadata is jsonb; chunks indexed for an upload/URL job carry job_id.
        return jdbcTemplate.update(
                "delete from vector_store where metadata->>'job_id' = ?", jobId);
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
