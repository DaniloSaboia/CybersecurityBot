package com.sentinel.kms.ingest;

import com.sentinel.kms.vectorstore.EmbeddingIndexService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobProcessorTest {

    @Mock IngestJobRepository jobs;
    @Mock IngestSourceRepository sources;
    @Mock DurableIngestQueue queue;
    @Mock TextExtractor extractor;
    @Mock Chunker chunker;
    @Mock EmbeddingIndexService index;
    @Mock UrlFetcher urlFetcher;
    @Mock MeterRegistry meters;
    @Mock Counter counter;
    @Mock Timer timer;

    private JobProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new JobProcessor(
                jobs, sources, queue, extractor, chunker, index, urlFetcher, meters, 1200, 150, 1024);
        when(meters.counter(any(), any(String[].class))).thenReturn(counter);
        when(meters.timer(any())).thenReturn(timer);
    }

    @Test
    void initialGenerationOneClaimReplacesAndIndexesTheDurableSource() {
        DurableJobClaim claim = new DurableJobClaim("job-1", "claim-token", 1);
        IngestJob job = durableJob("job-1");
        IngestSource source = IngestSource.upload(
                job.getSourceId(), job.getSourceRevisionId(), job.getId(), job.getName(), "text/plain",
                "first durable source".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<String> chunks = List.of("first durable", "durable source");
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(sources.findByJobId(job.getId())).thenReturn(Optional.of(source));
        when(queue.progress(eq(claim), any(JobStage.class), anyDouble(), anyInt())).thenReturn(true);
        when(queue.heartbeat(claim)).thenReturn(true);
        when(extractor.extract(any(byte[].class), eq(job.getName()))).thenReturn("first durable source");
        when(chunker.chunk("first durable source", 1200, 150)).thenReturn(chunks);
        when(index.replaceForClaim(eq(job.getId()), eq(1L), eq(claim.token()), eq(chunks), any(Map.class)))
                .thenReturn(chunks.size());
        when(queue.complete(claim, chunks.size())).thenReturn(true);

        processor.process(claim);

        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(index).replaceForClaim(eq(job.getId()), eq(1L), eq(claim.token()), eq(chunks), metadata.capture());
        assertThat(metadata.getValue()).containsEntry("owner_sub", "user-1")
                .containsEntry("source_id", job.getSourceId())
                .containsEntry("source_revision_id", job.getSourceRevisionId())
                .containsEntry("source_key", job.getSourceKey());
        verify(queue).complete(claim, chunks.size());
        verify(index, never()).index(any(), any());
    }

    @Test
    void lostLeaseBeforeReplacementCannotIndexOrMarkTheJobComplete() {
        DurableJobClaim claim = new DurableJobClaim("job-1", "claim-token", 1);
        IngestJob job = durableJob("job-1");
        IngestSource source = IngestSource.upload(
                job.getSourceId(), job.getSourceRevisionId(), job.getId(), job.getName(), "text/plain",
                "first durable source".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(sources.findByJobId(job.getId())).thenReturn(Optional.of(source));
        when(queue.progress(eq(claim), any(JobStage.class), anyDouble(), anyInt())).thenReturn(true);
        when(queue.heartbeat(claim)).thenReturn(false); // delete/retry/recovery won the fence

        processor.process(claim);

        verifyNoIndexReplacement();
        verify(queue, never()).complete(any(), anyInt());
    }

    private void verifyNoIndexReplacement() {
        verify(index, never()).replaceForClaim(any(), anyLong(), any(), any(), any());
        verify(index, never()).index(any(), any());
    }

    private static IngestJob durableJob(String id) {
        String sourceId = "655a2471-69ec-42ea-a4c8-3d818169c07b";
        String revisionId = "8c1f79d3-20d0-4f9a-9e1a-3e3fa70f8fa7";
        IngestJob job = new IngestJob();
        job.setId(id);
        job.setName("report.txt");
        job.setSource(IngestService.SOURCE_UPLOAD);
        job.setOwnerSub("user-1");
        job.setSourceId(sourceId);
        job.setSourceRevisionId(revisionId);
        job.setSourceKey(IngestSource.sourceKey(sourceId, revisionId));
        job.setIndexGeneration(1);
        return job;
    }
}
