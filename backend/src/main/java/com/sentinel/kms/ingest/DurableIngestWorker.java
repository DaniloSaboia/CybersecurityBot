package com.sentinel.kms.ingest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the durable queue.  The work itself is deliberately synchronous: one
 * scheduler thread per application instance avoids claiming a backlog into an
 * in-memory executor, while PostgreSQL leases allow many instances to work in
 * parallel safely and recover after a process stops.
 */
@Component
@ConditionalOnProperty(name = "app.ingest.worker-enabled", havingValue = "true", matchIfMissing = true)
public class DurableIngestWorker {

    private final DurableIngestQueue queue;
    private final JobProcessor processor;

    public DurableIngestWorker(DurableIngestQueue queue, JobProcessor processor) {
        this.queue = queue;
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${app.ingest.worker-poll-ms:750}")
    public void poll() {
        queue.claimNext().ifPresent(processor::process);
    }
}
