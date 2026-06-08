package com.sentinel.kms.kb;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

/**
 * Articles. Use {@link JpaSpecificationExecutor} for dynamic
 * category/severity/text filtering in the KB service.
 */
public interface ArticleRepository
        extends JpaRepository<Article, String>, JpaSpecificationExecutor<Article> {

    List<Article> findByCategoryId(String categoryId);

    List<Article> findBySeverity(String severity);
}
