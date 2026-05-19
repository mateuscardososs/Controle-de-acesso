package br.com.sport.accesscontrol.integration.sync;

import br.com.sport.accesscontrol.config.WebSocketConfig;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class IntegrationSyncRealtimePublisher {
    private final SimpMessagingTemplate messagingTemplate;

    public IntegrationSyncRealtimePublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void publish(br.com.sport.accesscontrol.common.PersonType type, UUID id, SyncStatus status, String message) {
        messagingTemplate.convertAndSend(WebSocketConfig.INTEGRATION_SYNC_TOPIC,
                new IntegrationSyncMessage(type, id, status, message, Instant.now()));
    }
}
