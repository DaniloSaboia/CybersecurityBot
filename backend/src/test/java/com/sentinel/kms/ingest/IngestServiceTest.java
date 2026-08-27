package com.sentinel.kms.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.net.InetAddress;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestServiceTest {

    @Mock IngestJobRepository jobs;
    @Mock ConnectorRepository connectors;
    @Mock JobProcessor processor;
    @Mock com.sentinel.kms.vectorstore.EmbeddingIndexService embeddings;

    private IngestService service;
    private final UrlGuard guard = new UrlGuard(host -> {
        try {
            return List.of(InetAddress.getByName("93.184.216.34"));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    });

    @BeforeEach
    void setUp() {
        service = new IngestService(jobs, connectors, processor, embeddings, guard, "pdf,txt,md");
    }

    @Test
    void uploadRejectsDisallowedExtension() {
        MockMultipartFile exe = new MockMultipartFile("file", "payload.exe", "application/octet-stream",
                new byte[]{1, 2, 3});
        assertThatThrownBy(() -> service.createUploadJob(exe, "user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported file type");
        verify(jobs, never()).save(any());
    }

    @Test
    void uploadRejectsEmptyFile() {
        MockMultipartFile empty = new MockMultipartFile("file", "notes.txt", "text/plain", new byte[0]);
        assertThatThrownBy(() -> service.createUploadJob(empty, "user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void uploadAcceptsAllowedExtensionAndStartsProcessing() {
        MockMultipartFile pdf = new MockMultipartFile("file", "report.PDF", "application/pdf",
                new byte[]{1, 2, 3});
        when(jobs.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IngestJobDto dto = service.createUploadJob(pdf, "user-1");

        ArgumentCaptor<IngestJob> saved = ArgumentCaptor.forClass(IngestJob.class);
        verify(jobs).save(saved.capture());
        assertThat(saved.getValue().getOwnerSub()).isEqualTo("user-1");
        assertThat(saved.getValue().getSourceUrl()).isNull();
        verify(processor).process(eq(saved.getValue().getId()), aryEq(new byte[]{1, 2, 3}), isNull(), isNull());
        assertThat(dto.name()).isEqualTo("report.PDF");
    }

    @Test
    void urlJobValidatesThenStoresUrlAndFetches() {
        when(jobs.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createUrlJob("https://example.com/advisory.html", "user-1");

        ArgumentCaptor<IngestJob> saved = ArgumentCaptor.forClass(IngestJob.class);
        verify(jobs).save(saved.capture());
        assertThat(saved.getValue().getSourceUrl()).isEqualTo("https://example.com/advisory.html");
        verify(processor).process(saved.getValue().getId(), null, null,
                "https://example.com/advisory.html");
    }

    @Test
    void urlJobRejectsPrivateHostBeforeCreatingAnything() {
        UrlGuard blocking = new UrlGuard(host -> {
            try {
                return List.of(InetAddress.getByName("192.168.1.5"));
            } catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
        });
        IngestService strict = new IngestService(jobs, connectors, processor, embeddings, blocking, "pdf");

        assertThatThrownBy(() -> strict.createUrlJob("http://internal.local/doc", "user-1"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jobs, never()).save(any());
        verify(processor, never()).process(any(), any(), any(), any());
    }

    @Test
    void deleteOnlyWorksForOwnedJobs() {
        IngestJob seeded = new IngestJob(); // owner == null (demo job)
        seeded.setId("job-1");
        when(jobs.findById("job-1")).thenReturn(Optional.of(seeded));
        assertThatThrownBy(() -> service.deleteJob("job-1", "user-1"))
                .isInstanceOf(NoSuchElementException.class);

        IngestJob other = new IngestJob();
        other.setId("job-2");
        other.setOwnerSub("user-2");
        when(jobs.findById("job-2")).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.deleteJob("job-2", "user-1"))
                .isInstanceOf(NoSuchElementException.class);

        IngestJob own = new IngestJob();
        own.setId("job-3");
        own.setOwnerSub("user-1");
        when(jobs.findById("job-3")).thenReturn(Optional.of(own));
        when(embeddings.deleteByJob("job-3")).thenReturn(4);
        assertThat(service.deleteJob("job-3", "user-1")).isEqualTo(4);
        verify(jobs).delete(own);
    }
}
