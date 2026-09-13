package com.sentinel.kms.kb;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

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
    public List<CategoryDto> categories(@AuthenticationPrincipal Jwt jwt) {
        return kbService.listCategories(jwt.getSubject());
    }

    @GetMapping("/articles")
    public ArticleListResponse articles(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String sort,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return kbService.search(jwt.getSubject(), q, category, severity, sort);
    }

    @GetMapping("/articles/{id}")
    public ArticleDto article(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return kbService.getArticle(jwt.getSubject(), id);
    }
}
