package com.sentinel.kms.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly NVD pull so the CVE feed stays current without anyone pressing the
 * sync button. Disabled unless {@code app.nvd.sync-enabled=true}; ingestion workers remain independently scheduled.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.nvd.sync-enabled", havingValue = "true")
public class NvdScheduler {

    private static final Logger log = LoggerFactory.getLogger(NvdScheduler.class);

    private final FeedSyncService feeds;

    public NvdScheduler(FeedSyncService feeds) {
        this.feeds = feeds;
    }

    /** 03:00 local time, nightly. */
    @Scheduled(cron = "${app.nvd.sync-cron:0 0 3 * * *}")
    public void nightlySync() {
        try {
            feeds.enqueue("nvd", "daily");
        } catch (Exception e) {
            log.warn("Scheduled NVD sync failed: {}", e.toString());
        }
    }
}
