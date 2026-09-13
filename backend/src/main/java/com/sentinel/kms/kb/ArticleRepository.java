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

    @Query("""
            select a.id from Article a
            where (:category is null or a.categoryId = :category)
              and (:severity is null or lower(a.severity) = lower(:severity))
            """)
    List<String> findIdsByCategoryAndSeverity(String category, String severity);
}
