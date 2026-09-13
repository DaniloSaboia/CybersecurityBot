package com.sentinel.kms.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobSimulatorTest {

    @Mock IngestJobRepository jobs;

    @Test
    void simulatorAdvancesOnlyExplicitDemoRowsRatherThanInferringFromSourceLabels() {
        IngestJob demo = new IngestJob();
        demo.setId("job-demo");
        demo.setDemo(true);
        demo.setSource(IngestService.SOURCE_UPLOAD); // source label intentionally looks real
        demo.setStage(JobStage.EXTRACTING.id());
        demo.setPct(10);
        when(jobs.findByDemoTrueAndDeletedAtIsNullAndStageNotIn(any()))
                .thenReturn(List.of(demo));

        new JobSimulator(jobs).tick();

        verify(jobs).findByDemoTrueAndDeletedAtIsNullAndStageNotIn(
                List.of(JobStage.INDEXED.id(), JobStage.FAILED.id(), JobStage.CANCELLED.id()));
        verify(jobs).save(demo);
        assertThat(demo.getPct()).isGreaterThan(10);
    }
}
