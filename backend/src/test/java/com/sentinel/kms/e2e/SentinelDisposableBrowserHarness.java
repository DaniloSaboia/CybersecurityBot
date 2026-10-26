package com.sentinel.kms.e2e;

import com.sentinel.kms.KmsApplication;
import com.sentinel.kms.kb.*;
import com.sentinel.kms.vectorstore.EmbeddingIndexService;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.*;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

/** Test-classpath-only launcher. Real security/persistence/retrieval; AI provider substitution. */
public final class SentinelDisposableBrowserHarness {
    public static final String PASSAGE = "Sentinel disposable procedure requires revoking active sessions before rotating credentials.";
    public static void main(String[] args) {
        if (!"1".equals(System.getenv("SENTINEL_REAL_STACK_E2E"))) {
            throw new IllegalStateException("Disposable browser harness requires explicit opt-in");
        }
        var context = SpringApplication.run(new Class<?>[]{KmsApplication.class, Providers.class}, args);
        var jdbc = context.getBean(JdbcTemplate.class);
        jdbc.update("insert into categories(id,label,abbr,doc_count,sort_order) values ('e2e','Disposable fixtures','E2E',1,0)");
        Article article = new Article();
        article.setId("E2E-9001");
        article.setTitle("Sentinel disposable procedure");
        article.setCategoryId("e2e"); article.setSeverity("high");
        article.setSource("Disposable local corpus"); article.setAuthor("Test harness");
        article.setConfidence("high"); article.setUpdatedOn(LocalDate.of(2026, 9, 6));
        article.setSnippet(PASSAGE);
        context.getBean(TrustedArticleRegistrationService.class).persistAndRegister(
                context.getBean(ArticleRepository.class), List.of(article), TrustedArticleSource.SEED);
        String revision = jdbc.queryForObject("select current_revision_id from article_governance where article_id='E2E-9001'", String.class);
        int indexed = context.getBean(EmbeddingIndexService.class).replaceForArticleRevision("E2E-9001", revision);
        if (indexed < 1) throw new IllegalStateException("Fixture did not index");
        System.out.println("SENTINEL_DISPOSABLE_READY provider_substitution=deterministic-chat-and-embeddings real_pgvector=true");
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Providers {
        @Bean EmbeddingModel disposableEmbeddingModel() {
            return new EmbeddingModel() {
                private float[] vector() { float[] v = new float[1024]; v[0] = 1; return v; }
                @Override public int dimensions() { return 1024; }
                @Override public float[] embed(org.springframework.ai.document.Document document) { return vector(); }
                @Override public EmbeddingResponse call(EmbeddingRequest request) {
                    return new EmbeddingResponse(java.util.stream.IntStream.range(0, request.getInstructions().size())
                            .mapToObj(i -> new Embedding(vector(), i)).toList());
                }
            };
        }
        @Bean VectorStore disposableVectorStore(JdbcTemplate jdbc, EmbeddingModel model) {
            return PgVectorStore.builder(jdbc, model).dimensions(1024).initializeSchema(false).build();
        }
        @Bean ChatModel disposableChatModel() {
            return new ChatModel() {
                @Override public ChatResponse call(Prompt prompt) {
                    String user = prompt.getUserMessage().getText();
                    var token = Pattern.compile("\\[\\[evidence:E2E-9001\\|[^\\r\\n]+?\\]\\]").matcher(user);
                    // Do not synthesize evidence: fail unless production retrieval supplied the fixture passage and token.
                    if (!user.contains(PASSAGE) || !user.contains("cobalt lantern") || !token.find()) throw new IllegalStateException("Expected real retrieved article and uploaded fixture evidence");
                    return new ChatResponse(List.of(new Generation(new AssistantMessage(
                            "Revoke active sessions before rotating credentials. " + token.group()))));
                }
                @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }
            };
        }
    }
}
