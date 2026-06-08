package com.sentinel.kms.ingest;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drives live progress for seeded/demo jobs so the UI shows movement without a
 * real pipeline behind them. Mirrors the prototype's client-side interval.
 *
 * <p>Safety: it only advances jobs that are NOT real manual uploads / URL imports
 * (those are owned by {@link JobProcessor}), and additionally skips any id the
 * processor is actively driving. Every step is idempotent and bounded.
 */
@Component
public class JobSimulator {

    private static final List<String> TERMINAL =
            List.of(JobStage.INDEXED.id(), JobStage.FAILED.id());

    private final IngestJobRepository jobs;
    private final JobProcessor processor;

    public JobSimulator(IngestJobRepository jobs, JobProcessor processor) {
        this.jobs = jobs;
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${app.ingest.simulate-latency-ms:1200}")
    public void tick() {
        List<IngestJob> inFlight = jobs.findByStageNotIn(TERMINAL);
        for (IngestJob job : inFlight) {
            if (isRealJob(job) || processor.isActive(job.getId())) {
                continue; // owned by the real pipeline — never touch.
            }
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

    private static boolean isRealJob(IngestJob job) {
        String src = job.getSource();
        return IngestService.SOURCE_UPLOAD.equals(src) || IngestService.SOURCE_URL.equals(src);
    }
}
