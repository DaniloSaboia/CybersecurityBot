package com.sentinel.kms.kb;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Knowledge Base REST surface. All responses are DTO records (never entities).
 */
@RestController
@RequestMapping("/api/kb")
public class KbController {

    private final KbService kbService;

    public KbController(KbService kbService) {
        this.kbService = kbService;
    }

    @GetMapping("/categories")
    public List<CategoryDto> categories() {
        return kbService.listCategories();
    }

    @GetMapping("/articles")
    public ArticleListResponse articles(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String sort
    ) {
        return kbService.search(q, category, severity, sort);
    }

    @GetMapping("/articles/{id}")
    public ArticleDto article(@PathVariable String id) {
        return kbService.getArticle(id);
    }
}
