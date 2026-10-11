package com.sentinel.kms.kb;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * Articles. Use {@link JpaSpecificationExecutor} for dynamic
 * category/severity/text filtering in the KB service.
 */
public interface ArticleRepository
        extends JpaRepository<Article, String>, JpaSpecificationExecutor<Article> {

    List<Article> findByCategoryId(String categoryId);

    List<Article> findBySeverity(String severity);

    /** Policy gates use identifiers first so unreadable article bodies are never fetched. */
    @Query("select a.id from Article a")
    List<String> findAllIds();

    // Select a query for each active-filter combination. Binding null inside
    // lower(:severity) can make PostgreSQL infer bytea instead of text.
    @Query("select a.id from Article a where a.categoryId = :category")
    List<String> findIdsByCategory(String category);

    /** severity must be non-null and normalized to lowercase by the caller. */
    @Query("select a.id from Article a where lower(a.severity) = :severity")
    List<String> findIdsBySeverity(String severity);

    @Query("""
            select a.id from Article a
            where a.categoryId = :category and lower(a.severity) = :severity
            """)
    List<String> findIdsByCategoryAndSeverity(String category, String severity);
}
