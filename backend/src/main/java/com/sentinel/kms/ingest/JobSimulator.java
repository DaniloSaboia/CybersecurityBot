package com.sentinel.kms.ingest;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drives live progress for seeded/demo jobs so the UI shows movement without a
 * real pipeline behind them. Mirrors the prototype's client-side interval.
 *
 * <p>Safety: only rows explicitly marked {@code is_demo} are eligible.  Source
 * labels and owner nullability are not a reliable authority for whether a row is
 * a demo, so a real durable job can never be moved by this component.
 */
@Component
public class JobSimulator {

    private static final List<String> TERMINAL =
            List.of(JobStage.INDEXED.id(), JobStage.FAILED.id(), JobStage.CANCELLED.id());

    private final IngestJobRepository jobs;
    public JobSimulator(IngestJobRepository jobs) {
        this.jobs = jobs;
    }

    @Scheduled(fixedDelayString = "${app.ingest.demo-poll-ms:1200}")
    public void tick() {
        List<IngestJob> inFlight = jobs.findByDemoTrueAndDeletedAtIsNullAndStageNotIn(TERMINAL);
        for (IngestJob job : inFlight) {
            nudge(job);
        }
    }

    private void nudge(IngestJob job) {
        JobStage current;
        try {
            current = JobStage.fromId(job.getStage());
        } catch (IllegalArgumentException e) {
            current = JobStage.QUEUED;
        }
        if (current.isTerminal()) {
            return;
        }

        double pct = job.getPct() + 8 + ThreadLocalRandom.current().nextInt(14);
        if (pct < 100) {
            job.setPct(pct);
        } else {
            // Stage complete: move forward.
            JobStage next = (current == JobStage.QUEUED) ? JobStage.EXTRACTING : current.next();
            job.setStage(next.id());
            job.setPct(next == JobStage.INDEXED ? 100 : 0);
            job.setWhenLabel("now");
            if (next == JobStage.CHUNKING && job.getChunks() <= 0) {
                job.setChunks(40 + ThreadLocalRandom.current().nextInt(380));
            }
        }
        jobs.save(job);
    }
}
