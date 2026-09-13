package com.sentinel.kms.ingest;

import com.sentinel.kms.audit.AuditActions;
import com.sentinel.kms.audit.AuditMetadata;
import com.sentinel.kms.audit.AuditOutcome;
import com.sentinel.kms.common.KnowledgeAuditEvent;
import com.sentinel.kms.common.KnowledgeAuditService;
import com.sentinel.kms.vectorstore.EmbeddingIndexService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestAuditTrailTest {

    private static final String OWNER = "alice";
    private static final String CONTENT_CANARY = "UPLOAD_CONTENT_CANARY_DO_NOT_AUDIT";
    private static final String URL_CANARY = "URL_PATH_CANARY_DO_NOT_AUDIT";
    private static final String ERROR_CANARY = "URL_ERROR_CANARY_DO_NOT_AUDIT";

    @Mock IngestJobRepository jobs;
    @Mock ConnectorRepository connectors;
    @Mock IngestSourceRepository sources;
    @Mock DurableIngestQueue queue;
    @Mock IngestIdempotencyStore idempotency;
    @Mock EmbeddingIndexService embeddings;
    @Mock KnowledgeAuditService audit;
    @Mock TextExtractor extractor;
    @Mock Chunker chunker;
    @Mock UrlFetcher urlFetcher;
    @Mock MeterRegistry meters;
    @Mock Counter counter;
    @Mock Timer timer;
    @Captor ArgumentCaptor<KnowledgeAuditEvent> events;

    private IngestService service;
    private JobProcessor processor;

    @BeforeEach
    void setUp() {
        UrlGuard guard = new UrlGuard(host -> {
            try {
                return List.of(InetAddress.getByName("93.184.216.34"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        service = new IngestService(
                jobs, connectors, sources, queue, idempotency, embeddings, guard, "txt", 1024, audit);
        processor = new JobProcessor(
                jobs, sources, queue, extractor, chunker, embeddings, urlFetcher, meters,
                1200, 150, 1024, audit);
    }

    @Test
    void createUploadUrlAndReplayAuditOnlyRedactedScalarProvenance() {
        when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sources.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MockMultipartFile upload = new MockMultipartFile(
                "file", "sensitive-name-" + CONTENT_CANARY + ".txt", "text/plain",
                CONTENT_CANARY.getBytes(StandardCharsets.UTF_8));
        service.createUploadJob(upload, OWNER, "lost-response-key");
        service.createUrlJob("https://example.com/" + URL_CANARY + ".txt", OWNER, "url-key");

        IngestJob replayed = durableJob("replay-job", "upload");
        when(idempotency.reserve(eq(OWNER), eq("upload"), eq("replay-key"), any(), any()))
                .thenReturn(new IngestIdempotencyReservation(false, replayed.getId()));
        when(jobs.findById(replayed.getId())).thenReturn(Optional.of(replayed));
        service.createUploadJob(upload, OWNER, "replay-key");

        verify(audit, org.mockito.Mockito.times(5)).record(events.capture());
        List<KnowledgeAuditEvent> captured = events.getAllValues();
        assertThat(captured).extracting(KnowledgeAuditEvent::action).containsExactlyInAnyOrder(
                AuditActions.INGEST_CREATE,
                AuditActions.INGEST_SOURCE_CREATED,
                AuditActions.INGEST_CREATE,
                AuditActions.INGEST_SOURCE_CREATED,
                AuditActions.INGEST_REQUEST_REPLAY);
        assertThat(captured).allSatisfy(this::assertRedactedScalarEvent);

        KnowledgeAuditEvent uploadSource = event(captured, AuditActions.INGEST_SOURCE_CREATED, "upload");
        assertThat(uploadSource.metadata()).containsEntry(AuditMetadata.SOURCE_TYPE, "upload")
                .containsEntry(AuditMetadata.RETAINED, true)
                .containsEntry(AuditMetadata.IDEMPOTENCY_KEY_PRESENT, true)
                .containsKey(AuditMetadata.SOURCE_ID)
                .containsKey(AuditMetadata.SOURCE_REVISION_ID)
                .containsKey(AuditMetadata.SIZE_BYTES)
                .doesNotContainValue(CONTENT_CANARY)
                .doesNotContainValue("lost-response-key");
        KnowledgeAuditEvent urlSource = event(captured, AuditActions.INGEST_SOURCE_CREATED, "url");
        assertThat(urlSource.metadata()).containsEntry(AuditMetadata.SOURCE_TYPE, "url")
                .containsEntry(AuditMetadata.RETAINED, false)
                .containsEntry(AuditMetadata.IDEMPOTENCY_KEY_PRESENT, true)
                .doesNotContainValue(URL_CANARY)
                .doesNotContainValue("url-key");
    }

    @Test
    void retryAndDeleteAuditAfterTheirDurableMutations() {
        IngestJob failed = durableJob("retry-job", "upload");
        failed.setStage(JobStage.FAILED.id());
        failed.setAttempt(4);
        failed.setIndexGeneration(3);
        IngestSource source = IngestSource.upload(
                failed.getSourceId(), failed.getSourceRevisionId(), failed.getId(), "report.txt", "text/plain",
                "retained".getBytes(StandardCharsets.UTF_8));
        when(jobs.findByIdAndOwnerSubAndDeletedAtIsNull(failed.getId(), OWNER)).thenReturn(Optional.of(failed));
        when(sources.findByJobId(failed.getId())).thenReturn(Optional.of(source));
        when(queue.requeueFailed(failed.getId(), OWNER)).thenAnswer(invocation -> {
            failed.setStage(JobStage.QUEUED.id());
            failed.setIndexGeneration(4);
            return true;
        });
        service.retry(failed.getId(), OWNER);

        when(queue.tombstone("delete-job", OWNER))
                .thenReturn(Optional.of(new DurableJobTombstone("delete-job", "source-id:revision-id")));
        when(embeddings.deleteBySource("source-id:revision-id")).thenReturn(7);
        when(sources.deleteByJobId("delete-job")).thenReturn(1);
        service.deleteJob("delete-job", OWNER);

        InOrder deleteOrder = org.mockito.Mockito.inOrder(queue, embeddings, sources, audit);
        deleteOrder.verify(queue).tombstone("delete-job", OWNER);
        deleteOrder.verify(embeddings).deleteBySource("source-id:revision-id");
        deleteOrder.verify(sources).deleteByJobId("delete-job");
        deleteOrder.verify(audit, org.mockito.Mockito.times(2)).record(any());
        verify(audit, org.mockito.Mockito.times(3)).record(events.capture());
        List<KnowledgeAuditEvent> captured = events.getAllValues();
        assertThat(captured).extracting(KnowledgeAuditEvent::action).containsExactlyInAnyOrder(
                AuditActions.INGEST_RETRY, AuditActions.INGEST_DELETE, AuditActions.INGEST_SOURCE_REMOVED);
        assertThat(captured).allSatisfy(this::assertRedactedScalarEvent);
        assertThat(event(captured, AuditActions.INGEST_RETRY, null).metadata())
                .containsEntry(AuditMetadata.ATTEMPT, 4L)
                .containsEntry(AuditMetadata.GENERATION, 4L);
        assertThat(event(captured, AuditActions.INGEST_DELETE, null).metadata())
                .containsEntry(AuditMetadata.VECTOR_ROWS_DELETED, 7L);
    }

    @Test
    void urlRetentionIndexedAndFailureUseFixedAuditCodesWithoutExternalText() {
        when(meters.counter(any(), any(String[].class))).thenReturn(counter);
        when(meters.timer(any())).thenReturn(timer);
        DurableJobClaim claim = new DurableJobClaim("url-job", "claim-token", 2);
        IngestJob urlJob = durableJob("url-job", "url");
        IngestSource urlSource = IngestSource.url(
                urlJob.getSourceId(), urlJob.getSourceRevisionId(), urlJob.getId(), "remote.txt",
                "https://example.com/" + URL_CANARY);
        byte[] fetched = "durable URL source".getBytes(StandardCharsets.UTF_8);
        when(jobs.findById(urlJob.getId())).thenReturn(Optional.of(urlJob));
        when(sources.findByJobId(urlJob.getId())).thenReturn(Optional.of(urlSource));
        when(queue.progress(eq(claim), any(JobStage.class), anyDouble(), anyInt())).thenReturn(true);
        when(queue.heartbeat(claim)).thenReturn(true);
        when(urlFetcher.fetch(urlSource.getSourceUrl())).thenReturn(fetched);
        when(queue.retainFetchedSource(eq(claim), eq(urlSource.getId()), eq(fetched), any())).thenReturn(true);
        when(extractor.extract(eq(fetched), eq(urlJob.getName()))).thenReturn("durable URL source");
        when(chunker.chunk("durable URL source", 1200, 150)).thenReturn(List.of("durable URL source"));
        when(embeddings.replaceForClaim(eq(urlJob.getId()), eq(2L), eq(claim.token()), any(), any()))
                .thenReturn(1);
        when(queue.complete(claim, 1)).thenReturn(true);

        processor.process(claim);

        InOrder successOrder = org.mockito.Mockito.inOrder(queue, embeddings, audit);
        successOrder.verify(queue).retainFetchedSource(eq(claim), eq(urlSource.getId()), eq(fetched), any());
        successOrder.verify(audit).record(any());
        successOrder.verify(embeddings)
                .replaceForClaim(eq(urlJob.getId()), eq(2L), eq(claim.token()), any(), any());
        successOrder.verify(queue).complete(claim, 1);
        successOrder.verify(audit).record(any());
        verify(audit, org.mockito.Mockito.times(2)).record(events.capture());
        List<KnowledgeAuditEvent> successEvents = events.getAllValues();
        assertThat(successEvents).extracting(KnowledgeAuditEvent::action).containsExactlyInAnyOrder(
                AuditActions.INGEST_SOURCE_RETAINED, AuditActions.INGEST_INDEXED);
        assertThat(successEvents).allSatisfy(this::assertRedactedScalarEvent);
        assertThat(event(successEvents, AuditActions.INGEST_SOURCE_RETAINED, null).metadata())
                .containsEntry(AuditMetadata.SOURCE_TYPE, "url")
                .containsEntry(AuditMetadata.RETAINED, true)
                .containsEntry(AuditMetadata.SIZE_BYTES, (long) fetched.length)
                .doesNotContainValue(URL_CANARY);

        DurableJobClaim badClaim = new DurableJobClaim("failed-url-job", "claim-token-2", 3);
        IngestJob failedUrlJob = durableJob("failed-url-job", "url");
        IngestSource failedUrlSource = IngestSource.url(
                failedUrlJob.getSourceId(), failedUrlJob.getSourceRevisionId(), failedUrlJob.getId(), "remote.txt",
                "https://example.com/fail");
        when(jobs.findById(failedUrlJob.getId())).thenReturn(Optional.of(failedUrlJob));
        when(sources.findByJobId(failedUrlJob.getId())).thenReturn(Optional.of(failedUrlSource));
        when(queue.progress(eq(badClaim), any(JobStage.class), anyDouble(), anyInt())).thenReturn(true);
        when(urlFetcher.fetch(failedUrlSource.getSourceUrl()))
                .thenThrow(new IllegalArgumentException(ERROR_CANARY));
        when(queue.fail(eq(badClaim), any())).thenReturn(true);

        processor.process(badClaim);

        ArgumentCaptor<KnowledgeAuditEvent> allEvents = ArgumentCaptor.forClass(KnowledgeAuditEvent.class);
        verify(audit, org.mockito.Mockito.times(3)).record(allEvents.capture());
        KnowledgeAuditEvent failure = event(allEvents.getAllValues(), AuditActions.INGEST_PROCESS_FAILED, null);
        assertThat(failure.action()).isEqualTo(AuditActions.INGEST_PROCESS_FAILED);
        assertThat(failure.metadata()).containsEntry(AuditMetadata.FAILURE_CODE, "URL_FETCH_FAILED")
                .doesNotContainValue(ERROR_CANARY)
                .doesNotContainValue(failedUrlSource.getSourceUrl());
        assertRedactedScalarEvent(failure);
    }

    @Test
    void deniedAndRacedRetryDeleteRequestsAuditFixedReasonCodesWithoutRawIdentifiers() {
        String rawJobId = "job/" + CONTENT_CANARY;
        when(jobs.findByIdAndOwnerSubAndDeletedAtIsNull(rawJobId, OWNER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.retry(rawJobId, OWNER))
                .isInstanceOf(NoSuchElementException.class);

        when(queue.tombstone(rawJobId, OWNER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteJob(rawJobId, OWNER))
                .isInstanceOf(NoSuchElementException.class);

        IngestJob failed = durableJob("retry-race-job", "upload");
        failed.setStage(JobStage.FAILED.id());
        IngestSource source = IngestSource.upload(
                failed.getSourceId(), failed.getSourceRevisionId(), failed.getId(), "report.txt", "text/plain",
                "retained".getBytes(StandardCharsets.UTF_8));
        when(jobs.findByIdAndOwnerSubAndDeletedAtIsNull(failed.getId(), OWNER)).thenReturn(Optional.of(failed));
        when(sources.findByJobId(failed.getId())).thenReturn(Optional.of(source));
        when(queue.requeueFailed(failed.getId(), OWNER)).thenReturn(false);
        assertThatThrownBy(() -> service.retry(failed.getId(), OWNER))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Job changed while retry was requested");

        verify(audit, org.mockito.Mockito.times(3)).record(events.capture());
        List<KnowledgeAuditEvent> captured = events.getAllValues();
        assertThat(captured).extracting(KnowledgeAuditEvent::outcome)
                .containsOnly(AuditOutcome.DENIED.wireValue(), AuditOutcome.CONFLICT.wireValue());
        assertThat(event(captured, AuditActions.INGEST_RETRY, null).metadata())
                .containsEntry(AuditMetadata.REASON_CODE, "RETRY_NOT_OWNER_OR_NOT_FOUND");
        assertThat(event(captured, AuditActions.INGEST_DELETE, null).metadata())
                .containsEntry(AuditMetadata.REASON_CODE, "DELETE_NOT_OWNER_OR_NOT_FOUND");
        assertThat(captured).anySatisfy(event -> assertThat(event.metadata())
                .containsEntry(AuditMetadata.REASON_CODE, "RETRY_RACE_LOST"));
        assertThat(captured).allSatisfy(this::assertRedactedScalarEvent);
        assertThat(captured).allSatisfy(event -> assertThat(event.resourceId())
                .doesNotContain(CONTENT_CANARY));
        assertThat(captured).anySatisfy(event -> assertThat(event.resourceId()).isEqualTo("redacted"));
    }

    @Test
    void rejectedCreateAndIdempotencyConflictAuditOnlyFixedCodesWithoutRequestCanaries() {
        MockMultipartFile unsupported = new MockMultipartFile(
                "file", "filename-" + CONTENT_CANARY + ".exe", "application/octet-stream",
                CONTENT_CANARY.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.createUploadJob(unsupported, OWNER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported file type");

        assertThatThrownBy(() -> service.createUrlJob("ftp://" + URL_CANARY, OWNER, "url-key-" + ERROR_CANARY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only http(s) URLs are allowed");

        MockMultipartFile valid = new MockMultipartFile(
                "file", "safe.txt", "text/plain", "body-".getBytes(StandardCharsets.UTF_8));
        String conflictingKey = "key-" + ERROR_CANARY;
        when(idempotency.reserve(eq(OWNER), eq("upload"), eq(conflictingKey), any(), any()))
                .thenThrow(new IdempotencyConflictException(ERROR_CANARY));
        assertThatThrownBy(() -> service.createUploadJob(valid, OWNER, conflictingKey))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(ERROR_CANARY);

        verify(audit, org.mockito.Mockito.times(3)).record(events.capture());
        List<KnowledgeAuditEvent> captured = events.getAllValues();
        assertThat(captured).extracting(KnowledgeAuditEvent::action)
                .containsOnly(AuditActions.INGEST_CREATE);
        assertThat(captured).extracting(KnowledgeAuditEvent::outcome).containsExactlyInAnyOrder(
                AuditOutcome.DENIED.wireValue(), AuditOutcome.DENIED.wireValue(), AuditOutcome.CONFLICT.wireValue());
        assertThat(captured).anySatisfy(event -> assertThat(event.metadata())
                .containsEntry(AuditMetadata.REASON_CODE, "UPLOAD_FILE_TYPE_UNSUPPORTED"));
        assertThat(captured).anySatisfy(event -> assertThat(event.metadata())
                .containsEntry(AuditMetadata.REASON_CODE, "URL_INVALID"));
        assertThat(captured).anySatisfy(event -> assertThat(event.metadata())
                .containsEntry(AuditMetadata.REASON_CODE, "IDEMPOTENCY_CONFLICT"));
        assertThat(captured).allSatisfy(this::assertRedactedScalarEvent);
        assertThat(captured).allSatisfy(event -> {
            assertThat(event.resourceId()).isEqualTo("redacted");
            assertThat(event.metadata().toString()).doesNotContain(
                    CONTENT_CANARY, URL_CANARY, ERROR_CANARY, conflictingKey);
        });
    }

    @Test
    void deleteCleanupFailuresAuditFixedFailureCodesAfterTombstoneBeforeRethrow() {
        when(queue.tombstone("vector-failure-job", OWNER))
                .thenReturn(Optional.of(new DurableJobTombstone("vector-failure-job", "source-one:revision-one")));
        when(embeddings.deleteBySource("source-one:revision-one"))
                .thenThrow(new IllegalStateException(ERROR_CANARY));
        assertThatThrownBy(() -> service.deleteJob("vector-failure-job", OWNER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ERROR_CANARY);

        when(queue.tombstone("source-failure-job", OWNER))
                .thenReturn(Optional.of(new DurableJobTombstone("source-failure-job", "source-two:revision-two")));
        when(embeddings.deleteBySource("source-two:revision-two")).thenReturn(3);
        when(sources.deleteByJobId("source-failure-job"))
                .thenThrow(new IllegalStateException("SOURCE_" + ERROR_CANARY));
        assertThatThrownBy(() -> service.deleteJob("source-failure-job", OWNER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ERROR_CANARY);

        InOrder failureOrder = org.mockito.Mockito.inOrder(queue, embeddings, sources, audit);
        failureOrder.verify(queue).tombstone("vector-failure-job", OWNER);
        failureOrder.verify(embeddings).deleteBySource("source-one:revision-one");
        failureOrder.verify(audit).record(any());
        failureOrder.verify(queue).tombstone("source-failure-job", OWNER);
        failureOrder.verify(embeddings).deleteBySource("source-two:revision-two");
        failureOrder.verify(sources).deleteByJobId("source-failure-job");
        failureOrder.verify(audit).record(any());
        verify(audit, org.mockito.Mockito.times(2)).record(events.capture());
        List<KnowledgeAuditEvent> captured = events.getAllValues();
        assertThat(captured).extracting(KnowledgeAuditEvent::outcome)
                .containsOnly(AuditOutcome.FAILURE.wireValue());
        assertThat(captured).anySatisfy(event -> assertThat(event.metadata())
                .containsEntry(AuditMetadata.FAILURE_CODE, "VECTOR_DELETE_FAILED"));
        assertThat(captured).anySatisfy(event -> assertThat(event.metadata())
                .containsEntry(AuditMetadata.FAILURE_CODE, "SOURCE_DELETE_FAILED"));
        assertThat(captured).allSatisfy(this::assertRedactedScalarEvent);
        assertThat(captured).allSatisfy(event -> assertThat(event.metadata().toString())
                .doesNotContain(ERROR_CANARY));
    }

    private KnowledgeAuditEvent event(List<KnowledgeAuditEvent> events, String action, String sourceType) {
        return events.stream()
                .filter(event -> action.equals(event.action()))
                .filter(event -> sourceType == null || sourceType.equals(event.metadata().get(AuditMetadata.SOURCE_TYPE)))
                .findFirst()
                .orElseThrow();
    }

    private void assertRedactedScalarEvent(KnowledgeAuditEvent event) {
        assertThat(event.metadata().keySet()).allSatisfy(key -> assertThat(key).isIn(
                AuditMetadata.SOURCE_TYPE,
                AuditMetadata.SOURCE_ID,
                AuditMetadata.SOURCE_REVISION_ID,
                AuditMetadata.SIZE_BYTES,
                AuditMetadata.RETAINED,
                AuditMetadata.IDEMPOTENCY_KEY_PRESENT,
                AuditMetadata.ATTEMPT,
                AuditMetadata.GENERATION,
                AuditMetadata.CHUNK_COUNT,
                AuditMetadata.VECTOR_ROWS_DELETED,
                AuditMetadata.FAILURE_CODE,
                AuditMetadata.REASON_CODE));
        assertThat(event.metadata().values()).allSatisfy(value ->
                assertThat(value).isInstanceOfAny(String.class, Long.class, Integer.class, Boolean.class));
        assertThat(event.metadata().toString()).doesNotContain(
                CONTENT_CANARY, URL_CANARY, ERROR_CANARY, "lost-response-key", "url-key", "replay-key");
    }

    private static IngestJob durableJob(String id, String sourceType) {
        IngestJob job = new IngestJob();
        job.setId(id);
        job.setName("report.txt");
        job.setSource("url".equals(sourceType) ? IngestService.SOURCE_URL : IngestService.SOURCE_UPLOAD);
        job.setOwnerSub(OWNER);
        job.setSourceId("source-" + id);
        job.setSourceRevisionId("revision-" + id);
        job.setSourceKey(IngestSource.sourceKey(job.getSourceId(), job.getSourceRevisionId()));
        job.setIndexGeneration(2);
        return job;
    }
}
