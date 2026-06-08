package com.sentinel.kms.kb;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Read-side service for the Knowledge Base surface. Equality filters
 * (category/severity) are pushed to the repository; free-text {@code q} matching
 * and sorting are finished in memory.
 */
@Service
public class KbService {

    private final ArticleRepository articleRepository;
    private final CategoryRepository categoryRepository;

    public KbService(ArticleRepository articleRepository, CategoryRepository categoryRepository) {
        this.articleRepository = articleRepository;
        this.categoryRepository = categoryRepository;
    }

    /** Categories ordered by their sortOrder, mapped to the wire DTO. */
    public List<CategoryDto> listCategories() {
        return categoryRepository.findAllByOrderBySortOrderAsc().stream()
                .map(CategoryDto::from)
                .toList();
    }

    /**
     * Filtered + sorted article search.
     *
     * @param q        case-insensitive substring over title+snippet+id+joined tags (skipped if blank)
     * @param category category id; skipped if null/blank/"all"
     * @param severity severity id; skipped if null/blank/"all"
     * @param sort     "updated" (default), "severity", or "reads"
     */
    public ArticleListResponse search(String q, String category, String severity, String sort) {
        boolean filterCategory = isActive(category);
        boolean filterSeverity = isActive(severity);

        List<Article> articles;
        if (filterCategory && filterSeverity) {
            String sev = severity.trim();
            articles = articleRepository.findByCategoryId(category.trim()).stream()
                    .filter(a -> sev.equalsIgnoreCase(a.getSeverity()))
                    .toList();
        } else if (filterCategory) {
            articles = articleRepository.findByCategoryId(category.trim());
        } else if (filterSeverity) {
            articles = articleRepository.findBySeverity(severity.trim());
        } else {
            articles = articleRepository.findAll();
        }

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

    /** Full article by id, or throws {@link NoSuchElementException} if absent. */
    public ArticleDto getArticle(String id) {
        Article article = articleRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Article not found: " + id));
        return ArticleDto.from(article);
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
