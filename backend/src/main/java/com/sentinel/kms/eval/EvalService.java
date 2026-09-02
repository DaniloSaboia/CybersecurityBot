package com.sentinel.kms.eval;

import com.sentinel.kms.vectorstore.EmbeddingIndexService;
import com.sentinel.kms.vectorstore.RetrievalFilters;
import com.sentinel.kms.vectorstore.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs the golden set through the exact retrieval path the Assistant uses
 * (hybrid RRF + optional scope filters) and scores it with standard IR
 * metrics: recall@k, MRR, nDCG@k. The report is returned to the caller (and
 * logged) so quality regressions are visible before users feel them.
 *
 * <p>Run it via {@code POST /api/eval/retrieval} whenever the chunking, the
 * embedding model, the fusion constants, or the KB content change.
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);

    private final GoldenSet goldenSet;
    private final EmbeddingIndexService index;
    private final int topK;
    private final double minScore;

    public EvalService(GoldenSet goldenSet,
                       EmbeddingIndexService index,
                       @Value("${app.rag.top-k:6}") int topK,
                       @Value("${app.rag.min-score:0.30}") double minScore) {
        this.goldenSet = goldenSet;
        this.index = index;
        this.topK = topK;
        this.minScore = minScore;
    }

    /** Per-case outcome. */
    public record CaseResult(
            String id,
            String question,
            List<String> retrieved,
            List<String> expected,
            double recallAtK,
            double mrr,
            double ndcgAtK,
            boolean pass) {
    }

    /** Aggregate report. */
    public record EvalReport(
            Instant ranAt,
            int cases,
            int topK,
            double meanRecallAtK,
            double meanMrr,
            double meanNdcgAtK,
            int passed,
            List<CaseResult> results) {
    }

    public EvalReport run() {
        List<CaseResult> results = new ArrayList<>(goldenSet.cases().size());
        for (EvalCase evalCase : goldenSet.cases()) {
            results.add(evaluate(evalCase));
        }

        double meanRecall = results.stream().mapToDouble(CaseResult::recallAtK).average().orElse(0);
        double meanMrr = results.stream().mapToDouble(CaseResult::mrr).average().orElse(0);
        double meanNdcg = results.stream().mapToDouble(CaseResult::ndcgAtK).average().orElse(0);
        int passed = (int) results.stream().filter(CaseResult::pass).count();

        EvalReport report = new EvalReport(
                Instant.now(), results.size(), topK, meanRecall, meanMrr, meanNdcg, passed, results);
        log.info("Retrieval eval: {} cases, recall@{}={}, mrr={}, ndcg@{}={}, passed={}/{}",
                results.size(), topK,
                String.format("%.3f", meanRecall),
                String.format("%.3f", meanMrr),
                topK,
                String.format("%.3f", meanNdcg),
                passed, results.size());
        return report;
    }

    private CaseResult evaluate(EvalCase evalCase) {
        Set<String> relevant = Set.copyOf(evalCase.expectedArticleIds());
        RetrievalFilters filters = new RetrievalFilters(evalCase.category(), evalCase.minSeverity());

        // Exactly the assistant's call: hybrid search, scope filters applied.
        List<RetrievedChunk> chunks = index.hybridSearch(
                evalCase.question(), topK, minScore, null,
                filters.isEmpty() ? null : filters);

        // Article ids in rank order, de-duplicated (several chunks may share one article).
        Set<String> seen = new LinkedHashSet<>();
        for (RetrievedChunk chunk : chunks) {
            String id = chunk.articleId();
            if (id != null && !id.isBlank()) {
                seen.add(id);
            }
        }
        List<String> ranked = new ArrayList<>(seen);

        double recall = RetrievalMetrics.recallAtK(ranked, relevant, topK);
        double mrr = RetrievalMetrics.mrr(ranked, relevant);
        double ndcg = RetrievalMetrics.ndcgAtK(ranked, relevant, topK);
        // Pass bar: at least one expected article surfaced in the top-k.
        boolean pass = recall > 0;

        return new CaseResult(evalCase.id(), evalCase.question(), ranked,
                evalCase.expectedArticleIds(), recall, mrr, ndcg, pass);
    }
}
