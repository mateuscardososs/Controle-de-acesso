package br.com.sport.accesscontrol.integration.intelbras.config;

import br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasIdentityCodec;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;

@Component
@ConfigurationProperties(prefix = "app.intelbras")
public class IntelbrasProperties {

    private Mode mode = Mode.FAKE;
    private String defaultUsername;
    private String defaultPassword;
    private Duration connectionTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(5);
    private int retryAttempts = 2;
    private Duration retryBackoff = Duration.ofMillis(300);
    private Duration healthInterval = Duration.ofSeconds(30);
    private boolean eventsPollingEnabled = true;
    private Duration eventsPollingInterval = Duration.ofSeconds(5);
    private Duration dedupWindow = Duration.ofSeconds(300);
    private IntelbrasIdentityCodec.Strategy identityStrategy = IntelbrasIdentityCodec.Strategy.DOCUMENT;
    private String timezone = "America/Recife";

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.FAKE : mode;
    }

    public String getDefaultUsername() {
        return defaultUsername;
    }

    public void setDefaultUsername(String defaultUsername) {
        this.defaultUsername = blankToNull(defaultUsername);
    }

    public String getDefaultPassword() {
        return defaultPassword;
    }

    public void setDefaultPassword(String defaultPassword) {
        this.defaultPassword = blankToNull(defaultPassword);
    }

    public Duration getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(Duration connectionTimeout) {
        if (connectionTimeout != null && !connectionTimeout.isNegative() && !connectionTimeout.isZero()) {
            this.connectionTimeout = connectionTimeout;
        }
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        if (readTimeout != null && !readTimeout.isNegative() && !readTimeout.isZero()) {
            this.readTimeout = readTimeout;
        }
    }

    public int getRetryAttempts() {
        return retryAttempts;
    }

    public void setRetryAttempts(int retryAttempts) {
        if (retryAttempts > 0 && retryAttempts <= 5) {
            this.retryAttempts = retryAttempts;
        }
    }

    public Duration getRetryBackoff() {
        return retryBackoff;
    }

    public void setRetryBackoff(Duration retryBackoff) {
        if (retryBackoff != null && !retryBackoff.isNegative()) {
            this.retryBackoff = retryBackoff;
        }
    }

    public Duration getHealthInterval() {
        return healthInterval;
    }

    public void setHealthInterval(Duration healthInterval) {
        if (healthInterval != null && !healthInterval.isNegative() && !healthInterval.isZero()) {
            this.healthInterval = healthInterval;
        }
    }

    public boolean isEventsPollingEnabled() {
        return eventsPollingEnabled;
    }

    public void setEventsPollingEnabled(boolean eventsPollingEnabled) {
        this.eventsPollingEnabled = eventsPollingEnabled;
    }

    public Duration getEventsPollingInterval() {
        return eventsPollingInterval;
    }

    public void setEventsPollingInterval(Duration eventsPollingInterval) {
        if (eventsPollingInterval != null && !eventsPollingInterval.isNegative() && !eventsPollingInterval.isZero()) {
            this.eventsPollingInterval = eventsPollingInterval;
        }
    }

    public Duration getDedupWindow() {
        return dedupWindow;
    }

    public void setDedupWindow(Duration dedupWindow) {
        if (dedupWindow != null && !dedupWindow.isNegative() && !dedupWindow.isZero()) {
            this.dedupWindow = dedupWindow;
        }
    }

    public IntelbrasIdentityCodec.Strategy getIdentityStrategy() {
        return identityStrategy;
    }

    public void setIdentityStrategy(IntelbrasIdentityCodec.Strategy identityStrategy) {
        this.identityStrategy = identityStrategy == null ? IntelbrasIdentityCodec.Strategy.DOCUMENT : identityStrategy;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        var value = blankToNull(timezone);
        if (value == null) {
            return;
        }
        try {
            ZoneId.of(value);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Invalid app.intelbras.timezone: " + value, exception);
        }
        this.timezone = value;
    }

    public ZoneId zoneId() {
        return ZoneId.of(timezone);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public SyncReaper getSyncReaper() {
        return syncReaper;
    }

    private final SyncReaper syncReaper = new SyncReaper();

    /**
     * Configuration for the background reaper that re-enqueues people stuck in PENDING_SYNC,
     * SYNCING or recoverable SYNC_FAILED so they are never lost after crashes / lost messages.
     */
    public static class SyncReaper {
        private boolean enabled = true;
        private Duration fixedDelay = Duration.ofMinutes(5);
        private Duration pendingThreshold = Duration.ofMinutes(5);
        private Duration syncingThreshold = Duration.ofMinutes(10);
        private Duration failedThreshold = Duration.ofMinutes(10);
        private int batchSize = 100;
        private int maxFailedRequeues = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getFixedDelay() {
            return fixedDelay;
        }

        public void setFixedDelay(Duration fixedDelay) {
            if (fixedDelay != null && !fixedDelay.isNegative() && !fixedDelay.isZero()) {
                this.fixedDelay = fixedDelay;
            }
        }

        public Duration getPendingThreshold() {
            return pendingThreshold;
        }

        public void setPendingThreshold(Duration pendingThreshold) {
            if (pendingThreshold != null && !pendingThreshold.isNegative()) {
                this.pendingThreshold = pendingThreshold;
            }
        }

        public Duration getSyncingThreshold() {
            return syncingThreshold;
        }

        public void setSyncingThreshold(Duration syncingThreshold) {
            if (syncingThreshold != null && !syncingThreshold.isNegative()) {
                this.syncingThreshold = syncingThreshold;
            }
        }

        public Duration getFailedThreshold() {
            return failedThreshold;
        }

        public void setFailedThreshold(Duration failedThreshold) {
            if (failedThreshold != null && !failedThreshold.isNegative()) {
                this.failedThreshold = failedThreshold;
            }
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            if (batchSize > 0) {
                this.batchSize = batchSize;
            }
        }

        public int getMaxFailedRequeues() {
            return maxFailedRequeues;
        }

        public void setMaxFailedRequeues(int maxFailedRequeues) {
            if (maxFailedRequeues >= 0) {
                this.maxFailedRequeues = maxFailedRequeues;
            }
        }
    }

    public enum Mode {
        FAKE,
        REAL
    }
}
