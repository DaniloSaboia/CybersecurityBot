package com.sentinel.kms.ingest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
class NvdSchedulerTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
        .withBean(FeedSyncService.class,()->mock(FeedSyncService.class)).withUserConfiguration(NvdScheduler.class);
    @Test void disabledFlagRemovesOnlyNvdScheduler() {
        runner.withPropertyValues("app.nvd.sync-enabled=false").run(context->{
            assertThat(context).doesNotHaveBean(NvdScheduler.class);
            assertThat(context).hasSingleBean(FeedSyncService.class);
        });
    }
    @Test void enabledFlagRegistersNvdScheduler() {
        runner.withPropertyValues("app.nvd.sync-enabled=true").run(context->assertThat(context).hasSingleBean(NvdScheduler.class));
    }
}
