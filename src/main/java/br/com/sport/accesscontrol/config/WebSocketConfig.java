package br.com.sport.accesscontrol.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    public static final String ACCESS_EVENTS_TOPIC = "/topic/access-events";
    public static final String DEVICE_STATUS_TOPIC = "/topic/device-status";
    public static final String SYSTEM_ALERTS_TOPIC = "/topic/system-alerts";
    public static final String INTEGRATION_SYNC_TOPIC = "/topic/integration-sync";

    private final String[] allowedOrigins;

    public WebSocketConfig(
            @Value("${app.cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000}") String allowedOrigins
    ) {
        this.allowedOrigins = java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toArray(String[]::new);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins(allowedOrigins);
        registry.addEndpoint("/ws-sockjs")
                .setAllowedOrigins(allowedOrigins)
                .withSockJS();
    }
}
