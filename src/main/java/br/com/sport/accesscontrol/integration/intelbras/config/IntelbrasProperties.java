package br.com.sport.accesscontrol.integration.intelbras.config;

import br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasIdentityCodec;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "app.intelbras")
public class IntelbrasProperties {

    private Mode mode = Mode.FAKE;
    private String defaultUsername;
    private String defaultPassword;
    private Duration connectionTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(5);
    private boolean eventsPollingEnabled = true;
    private Duration eventsPollingInterval = Duration.ofSeconds(5);
    private IntelbrasIdentityCodec.Strategy identityStrategy = IntelbrasIdentityCodec.Strategy.DOCUMENT;

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

    public IntelbrasIdentityCodec.Strategy getIdentityStrategy() {
        return identityStrategy;
    }

    public void setIdentityStrategy(IntelbrasIdentityCodec.Strategy identityStrategy) {
        this.identityStrategy = identityStrategy == null ? IntelbrasIdentityCodec.Strategy.DOCUMENT : identityStrategy;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public enum Mode {
        FAKE,
        REAL
    }
}
