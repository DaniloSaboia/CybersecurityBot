package com.sentinel.kms.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class IngestServiceTest {

    @Mock IngestJobRepository jobs;
    @Mock ConnectorRepository connectors;
    @Mock IngestSourceRepository sources;
    @Mock DurableIngestQueue queue;
    @Mock IngestIdempotencyStore idempotency;
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
        service = new IngestService(
                jobs, connectors, sources, queue, idempotency, embeddings, guard, "pdf,txt,md", 1024);
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
    void uploadRejectsBytesAboveTheDurablePostgresRetentionCapBeforeSavingAJob() {
        MockMultipartFile oversized = new MockMultipartFile(
                "file", "oversized.txt", "text/plain", new byte[1025]);

        assertThatThrownBy(() -> service.createUploadJob(oversized, "user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durable storage limit");
        verify(jobs, never()).save(any());
        verify(sources, never()).save(any());
    }

    @Test
    void uploadPersistsOriginalBytesWithServerGeneratedIdentityAndQueuesTheJob() {
        byte[] original = "forensic evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MockMultipartFile pdf = new MockMultipartFile("file", "report.PDF", "application/pdf", original);
        when(jobs.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sources.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IngestJobDto dto = service.createUploadJob(pdf, "user-1");

        ArgumentCaptor<IngestJob> jobCaptor = ArgumentCaptor.forClass(IngestJob.class);
        ArgumentCaptor<IngestSource> sourceCaptor = ArgumentCaptor.forClass(IngestSource.class);
        verify(jobs).save(jobCaptor.capture());
        verify(sources).save(sourceCaptor.capture());
        IngestJob job = jobCaptor.getValue();
        IngestSource source = sourceCaptor.getValue();
        assertThat(job.getOwnerSub()).isEqualTo("user-1");
        assertThat(job.getId()).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        assertThat(job.getSourceId()).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        assertThat(job.getSourceRevisionId()).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        assertThat(job.getSourceKey()).isEqualTo(job.getSourceId() + ":" + job.getSourceRevisionId());
        assertThat(job.getStage()).isEqualTo(JobStage.QUEUED.id());
        assertThat(job.getIndexGeneration()).isEqualTo(1);
        assertThat(job.isDemo()).isFalse();
        assertThat(source.getJobId()).isEqualTo(job.getId());
        assertThat(source.getSourceBytes()).containsExactly(original);
        assertThat(source.getSourceSha256()).isEqualTo(IngestSource.sha256(original));
        assertThat(source.getSourceKey()).isEqualTo(job.getSourceKey());
        verifyNoInteractions(queue);
        assertThat(dto.name()).isEqualTo("report.PDF");
    }

    @Test
    void retryOfUploadRequeuesThePersistedSourceWithoutSynthesizedText() {
        byte[] original = "incident report: CVE-2026-1234".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        IngestJob job = failedUploadJob("job-1");
        IngestSource source = IngestSource.upload(
                job.getSourceId(), job.getSourceRevisionId(), job.getId(), "incident.txt", "text/plain", original);
        when(jobs.findByIdAndOwnerSubAndDeletedAtIsNull(job.getId(), "user-1"))
                .thenReturn(Optional.of(job));
        when(sources.findByJobId(job.getId())).thenReturn(Optional.of(source));
        when(queue.requeueFailed(job.getId(), "user-1")).thenAnswer(invocation -> {
            job.setStage(JobStage.QUEUED.id());
            job.setIndexGeneration(2);
            return true;
        });

        IngestJobDto retried = service.retry(job.getId(), "user-1");

        verify(sources).findByJobId(job.getId());
        verify(queue).requeueFailed(job.getId(), "user-1");
        verifyNoInteractions(embeddings);
        assertThat(retried.stage()).isEqualTo(JobStage.QUEUED.id());
        assertThat(retried.error()).isNull();
    }

    @Test
    void retryRefusesAnUploadWhoseOriginalBytesWereNeverRetained() {
        IngestJob job = failedUploadJob("job-1");
        IngestSource source = IngestSource.url(
                job.getSourceId(), job.getSourceRevisionId(), job.getId(), "missing.txt", "https://example.com/missing");
        source.setSourceType("upload");
        when(jobs.findByIdAndOwnerSubAndDeletedAtIsNull(job.getId(), "user-1"))
                .thenReturn(Optional.of(job));
        when(sources.findByJobId(job.getId())).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> service.retry(job.getId(), "user-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Original source is unavailable");
        verify(queue, never()).requeueFailed(any(), any());
    }

    @Test
    void retryRejectsAnActiveJobInsteadOfLaunchingASecondWorker() {
        IngestJob job = failedUploadJob("job-1");
        job.setStage(JobStage.EMBEDDING.id());
        when(jobs.findByIdAndOwnerSubAndDeletedAtIsNull(job.getId(), "user-1"))
                .thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.retry(job.getId(), "user-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Only failed jobs can be retried");
        verifyNoInteractions(sources, queue, embeddings);
    }

    @Test
    void urlJobPersistsDurableUrlDescriptorAndQueuesIt() {
        when(jobs.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sources.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createUrlJob("https://example.com/advisory.html", "user-1");

        ArgumentCaptor<IngestJob> jobCaptor = ArgumentCaptor.forClass(IngestJob.class);
        ArgumentCaptor<IngestSource> sourceCaptor = ArgumentCaptor.forClass(IngestSource.class);
        verify(jobs).save(jobCaptor.capture());
        verify(sources).save(sourceCaptor.capture());
        assertThat(jobCaptor.getValue().getSourceUrl()).isEqualTo("https://example.com/advisory.html");
        assertThat(sourceCaptor.getValue().getSourceUrl()).isEqualTo("https://example.com/advisory.html");
        assertThat(sourceCaptor.getValue().getSourceBytes()).isNull();
        assertThat(sourceCaptor.getValue().getSourceKey()).isEqualTo(jobCaptor.getValue().getSourceKey());
        verifyNoInteractions(queue);
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
        IngestService strict = new IngestService(
                jobs, connectors, sources, queue, idempotency, embeddings, blocking, "pdf", 1024);

        assertThatThrownBy(() -> strict.createUrlJob("http://internal.local/doc", "user-1"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jobs, never()).save(any());
        verify(sources, never()).save(any());
    }

    @Test
    void deleteOnlyWorksForOwnedJobs() {
        when(queue.tombstone("job-1", "user-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteJob("job-1", "user-1"))
                .isInstanceOf(NoSuchElementException.class);

        when(queue.tombstone("job-2", "user-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteJob("job-2", "user-1"))
                .isInstanceOf(NoSuchElementException.class);

        when(queue.tombstone("job-3", "user-1"))
                .thenReturn(Optional.of(new DurableJobTombstone("job-3", "source:revision")));
        when(embeddings.deleteBySource("source:revision")).thenReturn(4);
        assertThat(service.deleteJob("job-3", "user-1")).isEqualTo(4);
        var order = inOrder(queue, embeddings, sources);
        order.verify(queue).tombstone("job-3", "user-1");
        order.verify(embeddings).deleteBySource("source:revision");
        order.verify(sources).deleteByJobId("job-3");

        when(queue.tombstone("legacy-job", "user-1"))
                .thenReturn(Optional.of(new DurableJobTombstone("legacy-job", null)));
        when(embeddings.deleteByJob("legacy-job")).thenReturn(2);
        assertThat(service.deleteJob("legacy-job", "user-1")).isEqualTo(2);
        verify(embeddings).deleteByJob("legacy-job");
    }

    @Test
    void keyedUploadReplayReturnsTheOriginalJobWithoutCreatingAnotherSource() {
        byte[] original = "same retained evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "same.txt", "text/plain", original);
        AtomicReference<IngestJob> created = new AtomicReference<>();
        when(jobs.save(any())).thenAnswer(invocation -> {
            IngestJob job = invocation.getArgument(0);
            created.set(job);
            return job;
        });
        when(sources.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(idempotency.reserve(eq("user-1"), eq("upload"), eq("retry-key"), any(), any()))
                .thenAnswer(invocation -> {
                    String candidateJobId = invocation.getArgument(4);
                    return created.get() == null
                            ? IngestIdempotencyReservation.claimed(candidateJobId)
                            : IngestIdempotencyReservation.replay(created.get().getId());
                });
        when(jobs.findById(any())).thenAnswer(invocation -> Optional.ofNullable(created.get()));

        IngestJobDto first = service.createUploadJob(file, "user-1", "retry-key");
        IngestJobDto replay = service.createUploadJob(file, "user-1", "retry-key");

        assertThat(replay.id()).isEqualTo(first.id());
        verify(jobs, times(1)).save(any());
        verify(sources, times(1)).save(any());
        verify(idempotency, times(2)).reserve(eq("user-1"), eq("upload"), eq("retry-key"), any(), any());
    }

    @Test
    void keyedUploadWithDifferentPayloadConflictsWithoutMutatingTheOriginalSource() {
        MockMultipartFile firstFile = new MockMultipartFile(
                "file", "same.txt", "text/plain", "original bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockMultipartFile changedFile = new MockMultipartFile(
                "file", "same.txt", "text/plain", "changed bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AtomicReference<IngestSource> persisted = new AtomicReference<>();
        when(idempotency.reserve(eq("user-1"), eq("upload"), eq("retry-key"), any(), any()))
                .thenAnswer(invocation -> IngestIdempotencyReservation.claimed(invocation.getArgument(4)))
                .thenThrow(new IdempotencyConflictException("Idempotency-Key was already used for a different request"));
        when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sources.save(any())).thenAnswer(invocation -> {
            IngestSource source = invocation.getArgument(0);
            persisted.set(source);
            return source;
        });

        service.createUploadJob(firstFile, "user-1", "retry-key");

        assertThatThrownBy(() -> service.createUploadJob(changedFile, "user-1", "retry-key"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("different request");
        assertThat(persisted.get().getSourceBytes()).containsExactly(
                "original bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        verify(sources, times(1)).save(any());
    }

    @Test
    void keyedUrlReplayReturnsOriginalButADifferentUrlConflicts() {
        AtomicReference<IngestJob> created = new AtomicReference<>();
        when(jobs.save(any())).thenAnswer(invocation -> {
            IngestJob job = invocation.getArgument(0);
            created.set(job);
            return job;
        });
        when(sources.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(idempotency.reserve(eq("user-1"), eq("url"), eq("url-key"), any(), any()))
                .thenAnswer(invocation -> {
                    String candidateJobId = invocation.getArgument(4);
                    return created.get() == null
                            ? IngestIdempotencyReservation.claimed(candidateJobId)
                            : IngestIdempotencyReservation.replay(created.get().getId());
                })
                .thenAnswer(invocation -> IngestIdempotencyReservation.replay(created.get().getId()))
                .thenThrow(new IdempotencyConflictException("Idempotency-Key was already used for a different request"));
        when(jobs.findById(any())).thenAnswer(invocation -> Optional.ofNullable(created.get()));

        IngestJobDto first = service.createUrlJob("https://example.com/one", "user-1", "url-key");
        IngestJobDto replay = service.createUrlJob("https://example.com/one", "user-1", "url-key");

        assertThat(replay.id()).isEqualTo(first.id());
        assertThatThrownBy(() -> service.createUrlJob("https://example.com/two", "user-1", "url-key"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("different request");
        verify(jobs, times(1)).save(any());
        verify(sources, times(1)).save(any());
    }

    @Test
    void unkeyedUploadsRemainDistinctAndDoNotCreateAnIdempotencyLedgerEntry() {
        MockMultipartFile firstFile = new MockMultipartFile("file", "one.txt", "text/plain", new byte[]{1});
        MockMultipartFile secondFile = new MockMultipartFile("file", "one.txt", "text/plain", new byte[]{1});
        when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sources.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        IngestJobDto first = service.createUploadJob(firstFile, "user-1");
        IngestJobDto second = service.createUploadJob(secondFile, "user-1");

        assertThat(second.id()).isNotEqualTo(first.id());
        verify(jobs, times(2)).save(any());
        verify(sources, times(2)).save(any());
        verifyNoInteractions(idempotency);
    }

    @Test
    void uploadIdempotencyFingerprintBindsTheWholeNormalizedRequestPayload() {
        byte[] filename = "evidence.txt".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] contentType = "text/plain".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bytes = "exact retained bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String original = IngestService.payloadFingerprint("upload", filename, contentType, bytes);

        assertThat(IngestService.payloadFingerprint("upload",
                "renamed.txt".getBytes(java.nio.charset.StandardCharsets.UTF_8), contentType, bytes))
                .isNotEqualTo(original);
        assertThat(IngestService.payloadFingerprint("upload", filename,
                "application/octet-stream".getBytes(java.nio.charset.StandardCharsets.UTF_8), bytes))
                .isNotEqualTo(original);
        assertThat(IngestService.payloadFingerprint("upload", filename, contentType,
                "changed retained bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isNotEqualTo(original);
        assertThat(IngestService.payloadFingerprint("url", filename, contentType, bytes))
                .isNotEqualTo(original);
    }

    private static IngestJob failedUploadJob(String id) {
        String sourceId = "0b22a0eb-3b9f-4b96-8e66-1a9c9e77300d";
        String revisionId = "d7dc9e67-916d-4b7b-a5e6-9db5f79f5343";
        IngestJob job = new IngestJob();
        job.setId(id);
        job.setOwnerSub("user-1");
        job.setSource(IngestService.SOURCE_UPLOAD);
        job.setSourceId(sourceId);
        job.setSourceRevisionId(revisionId);
        job.setSourceKey(IngestSource.sourceKey(sourceId, revisionId));
        job.setStage(JobStage.FAILED.id());
        job.setIndexGeneration(1);
        return job;
    }
}
