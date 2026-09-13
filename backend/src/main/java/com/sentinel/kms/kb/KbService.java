package com.sentinel.kms.kb;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Read-side service for the Knowledge Base surface. Equality filters
 * (category/severity) are pushed to the repository; free-text {@code q} matching
 * and sorting are finished in memory.
 */
@Service
public class KbService {

    private final ArticleRepository articleRepository;
    private final CategoryRepository categoryRepository;
    private final KnowledgeAccessService knowledgeAccessService;

    public KbService(ArticleRepository articleRepository, CategoryRepository categoryRepository,
                     KnowledgeAccessService knowledgeAccessService) {
        this.articleRepository = articleRepository;
        this.categoryRepository = categoryRepository;
        this.knowledgeAccessService = knowledgeAccessService;
    }

    /** Categories ordered by their sortOrder, mapped to the wire DTO. */
    public List<CategoryDto> listCategories(String subject) {
        Set<String> readableIds = readableIds(subject, articleRepository.findAllIds());
        Set<String> readableCategories = articleRepository.findAllById(readableIds).stream()
                .map(Article::getCategoryId)
                .filter(category -> category != null && !category.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        return categoryRepository.findAllByOrderBySortOrderAsc().stream()
                .filter(category -> readableCategories.contains(category.getId()))
                .map(CategoryDto::from)
                .toList();
    }

    /** Compatibility path intentionally sees no governed data without an authenticated subject. */
    public List<CategoryDto> listCategories() {
        return listCategories("");
    }

    /**
     * Filtered + sorted article search.
     *
     * @param q        case-insensitive substring over title+snippet+id+joined tags (skipped if blank)
     * @param category category id; skipped if null/blank/"all"
     * @param severity severity id; skipped if null/blank/"all"
     * @param sort     "updated" (default), "severity", or "reads"
     */
    public ArticleListResponse search(String subject, String q, String category, String severity, String sort) {
        boolean filterCategory = isActive(category);
        boolean filterSeverity = isActive(severity);

        List<String> candidateIds;
        if (filterCategory || filterSeverity) {
            candidateIds = articleRepository.findIdsByCategoryAndSeverity(
                    filterCategory ? category.trim() : null,
                    filterSeverity ? severity.trim() : null);
        } else {
            candidateIds = articleRepository.findAllIds();
        }
        // Resolve policy IDs before fetching any article body, IOC, or source field.
        List<Article> articles = articleRepository.findAllById(readableIds(subject, candidateIds));

        if (q != null && !q.isBlank()) {
            String needle = q.trim().toLowerCase();
            articles = articles.stream()
                    .filter(a -> matchesQuery(a, needle))
                    .toList();
        }

        List<ArticleDto> items = articles.stream()
                .sorted(comparatorFor(sort))
                .map(ArticleDto::from)
                .toList();

        return new ArticleListResponse(items, items.size());
    }

    /** Compatibility path intentionally sees no governed data without an authenticated subject. */
    public ArticleListResponse search(String q, String category, String severity, String sort) {
        return search("", q, category, severity, sort);
    }

    /** Full article by id, or throws {@link NoSuchElementException} if absent. */
    public ArticleDto getArticle(String subject, String id) {
        if (!knowledgeAccessService.canReadArticle(subject, id).allowed()) {
            // Preserve the historical 404 shape rather than revealing unreadable ids.
            throw new NoSuchElementException("Article not found: " + id);
        }
        Article article = articleRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Article not found: " + id));
        return ArticleDto.from(article);
    }

    /** Compatibility path intentionally sees no governed data without an authenticated subject. */
    public ArticleDto getArticle(String id) {
        return getArticle("", id);
    }

    private Set<String> readableIds(String subject, List<String> candidateIds) {
        return knowledgeAccessService.readableArticleIds(subject, candidateIds);
    }

    private static boolean isActive(String value) {
        return value != null && !value.isBlank() && !"all".equalsIgnoreCase(value.trim());
    }

    private static boolean matchesQuery(Article a, String needle) {
        if (contains(a.getTitle(), needle)) return true;
        if (contains(a.getSnippet(), needle)) return true;
        if (contains(a.getId(), needle)) return true;
        List<String> tags = a.getTags();
        if (tags != null) {
            String joined = String.join(" ", tags).toLowerCase();
            return joined.contains(needle);
        }
        return false;
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase().contains(needle);
    }

    private static Comparator<Article> comparatorFor(String sort) {
        if ("severity".equalsIgnoreCase(sort)) {
            return Comparator.comparingInt((Article a) -> Severity.rankOf(a.getSeverity())).reversed();
        }
        if ("reads".equalsIgnoreCase(sort)) {
            return Comparator.comparingInt(Article::getReads).reversed();
        }
        // default: "updated" — most recent first, nulls last
        return Comparator.comparing(
                Article::getUpdatedOn,
                Comparator.nullsLast(Comparator.naturalOrder())
        ).reversed();
    }
}
