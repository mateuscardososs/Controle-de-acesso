package br.com.sport.accesscontrol.integration.sync;

import br.com.sport.accesscontrol.common.messaging.IntegrationEventPublisher;
import br.com.sport.accesscontrol.integration.intelbras.config.IntelbrasProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Background safety net: every {@code app.intelbras.sync-reaper.fixed-delay} it re-enqueues people
 * that got stuck in PENDING_SYNC / SYNCING / recoverable SYNC_FAILED (e.g. after a crash, a lost
 * RabbitMQ message, or a partial publish). It reuses the normal {@link IntegrationEventPublisher}
 * so the worker path is identical to a fresh registration.
 *
 * <p>The DB claim (transition + lock) happens in {@link IntelbrasSyncReaperService#claim} and is
 * committed before this class publishes, so a crash between claim and publish simply leaves the row
 * to be picked up again on the next run.
 */
@Component
@ConditionalOnProperty(prefix = "app.intelbras.sync-reaper", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IntelbrasSyncReaper {

    private static final Logger log = LoggerFactory.getLogger(IntelbrasSyncReaper.class);

    private final IntelbrasSyncReaperService reaperService;
    private final IntegrationEventPublisher eventPublisher;
    private final IntelbrasProperties properties;
    private final MeterRegistry meterRegistry;

    private final AtomicLong pendingStuckGauge = new AtomicLong(0);
    private final AtomicLong syncingStuckGauge = new AtomicLong(0);
    private final AtomicLong failedRetriableGauge = new AtomicLong(0);

    public IntelbrasSyncReaper(IntelbrasSyncReaperService reaperService,
                               IntegrationEventPublisher eventPublisher,
                               IntelbrasProperties properties,
                               MeterRegistry meterRegistry) {
        this.reaperService = reaperService;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        meterRegistry.gauge("sync.pending.stuck", pendingStuckGauge);
        meterRegistry.gauge("sync.syncing.stuck", syncingStuckGauge);
        meterRegistry.gauge("sync.failed.retriable", failedRetriableGauge);
    }

    // Short initial delay so a restarted instance starts healing stuck rows within ~1 min,
    // then runs on the configured fixed delay.
    @Scheduled(fixedDelayString = "#{@intelbrasProperties.syncReaper.fixedDelay.toMillis()}",
            initialDelay = 60_000L)
    public void reap() {
        var start = System.currentTimeMillis();
        var sample = Timer.start(meterRegistry);
        log.info("SYNC_REAPER_STARTED");

        IntelbrasSyncReaperService.ClaimResult result;
        try {
            result = reaperService.claim(properties.getSyncReaper());
        } catch (Exception exception) {
            sample.stop(meterRegistry.timer("sync.reaper.duration", "result", "ERROR"));
            log.error("SYNC_REAPER_CLAIM_FAILED error={}", exception.getMessage());
            return;
        }

        pendingStuckGauge.set(result.pendingStuck());
        syncingStuckGauge.set(result.syncingStuck());
        failedRetriableGauge.set(result.failedRetriable());
        log.info("SYNC_REAPER_FOUND_PENDING count={}", result.pendingStuck());
        log.info("SYNC_REAPER_FOUND_SYNCING count={}", result.syncingStuck());
        log.info("SYNC_REAPER_FOUND_FAILED_RETRIABLE count={}", result.failedRetriable());

        var published = 0;
        for (var message : result.toPublish()) {
            try {
                eventPublisher.publishIntelbrasSync(message);
                published++;
                meterRegistry.counter("sync.reaper.requeued", "type", message.personType().name()).increment();
            } catch (Exception exception) {
                log.warn("SYNC_REAPER_PUBLISH_FAILED personType={} personId={} error={}",
                        message.personType(), message.personId(), exception.getMessage());
            }
        }
        if (result.skipped() > 0) {
            meterRegistry.counter("sync.reaper.skipped").increment(result.skipped());
        }

        sample.stop(meterRegistry.timer("sync.reaper.duration", "result", "OK"));
        log.info("SYNC_REAPER_FINISHED requeued={} skipped={} durationMs={}",
                published, result.skipped(), System.currentTimeMillis() - start);
    }
}
