package com.sentinel.kms.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.kms.ingest.ConnectorRepository;
import com.sentinel.kms.ingest.IngestJobRepository;
import com.sentinel.kms.kb.Article;
import com.sentinel.kms.kb.ArticleRepository;
import com.sentinel.kms.kb.CategoryRepository;
import com.sentinel.kms.kb.TrustedArticleRegistrationService;
import com.sentinel.kms.kb.TrustedArticleSource;
import com.sentinel.kms.vectorstore.EmbeddingIndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class DataSeederCanonicalPublicationTest {

    @Mock ArticleRepository articles;
    @Mock CategoryRepository categories;
    @Mock ConnectorRepository connectors;
    @Mock IngestJobRepository jobs;
    @Mock EmbeddingIndexService embeddingIndex;
    @Mock TrustedArticleRegistrationService registration;

    @Test
    void freshSeedUsesTrustedRegistrationAndNeverIndexesLegacyArticleChunks() throws Exception {
        when(articles.count()).thenReturn(0L);
        DataSeeder seeder = new DataSeeder(true, articles, categories, connectors, jobs,
                embeddingIndex, new ObjectMapper(), registration);

        seeder.run();

        ArgumentCaptor<List<Article>> seeded = ArgumentCaptor.forClass(List.class);
        verify(registration).persistAndRegister(eq(articles), seeded.capture(), eq(TrustedArticleSource.SEED));
        assertThat(seeded.getValue())
                .isNotEmpty()
                .allSatisfy(article -> assertThat(article.getId()).isNotBlank());
        verifyNoInteractions(embeddingIndex);
    }

    @Test
    void disabledDemoSeedStillProvisionsConnectorsWithoutPublishingArticlesOrJobs() throws Exception {
        DataSeeder seeder = new DataSeeder(false, articles, categories, connectors, jobs,
                embeddingIndex, new ObjectMapper(), registration);

        seeder.run();

        verify(connectors, atLeastOnce()).save(any());
        verify(categories).saveAll(any());
        verifyNoInteractions(articles, jobs, registration, embeddingIndex);
    }
}
