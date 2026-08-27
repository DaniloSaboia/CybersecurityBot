package com.sentinel.kms.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly NVD pull so the CVE feed stays current without anyone pressing the
 * sync button. Disabled unless {@code app.nvd.sync-enabled=true} (the enabling
 * config lives in {@code SchedulingConfig}, so no scheduling infrastructure is
 * active at all when off).
 */
@Component
public class NvdScheduler {

    private static final Logger log = LoggerFactory.getLogger(NvdScheduler.class);

    private final NvdConnectorService nvd;
    private final int syncDays;
    private final int syncMax;

    public NvdScheduler(NvdConnectorService nvd,
                        @Value("${app.nvd.sync-days:2}") int syncDays,
                        @Value("${app.nvd.sync-max:50}") int syncMax) {
        this.nvd = nvd;
        this.syncDays = syncDays;
        this.syncMax = syncMax;
    }

    /** 03:00 local time, nightly. */
    @Scheduled(cron = "${app.nvd.sync-cron:0 0 3 * * *}")
    public void nightlySync() {
        try {
            NvdConnectorService.SyncResult result = nvd.sync(syncDays, syncMax);
            log.info("Scheduled NVD sync: {} fetched, {} new articles, {} embedded",
                    result.fetched(), result.created(), result.embedded());
        } catch (Exception e) {
            log.warn("Scheduled NVD sync failed: {}", e.toString());
        }
    }
}
