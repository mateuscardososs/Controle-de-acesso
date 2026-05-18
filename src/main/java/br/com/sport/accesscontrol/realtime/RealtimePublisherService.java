package br.com.sport.accesscontrol.realtime;

import br.com.sport.accesscontrol.config.WebSocketConfig;
import br.com.sport.accesscontrol.devices.Device;
import br.com.sport.accesscontrol.events.AccessEvent;
import br.com.sport.accesscontrol.realtime.dto.RealtimeDeviceStatusMessage;
import br.com.sport.accesscontrol.realtime.dto.SystemAlertMessage;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class RealtimePublisherService {

    private final SimpMessagingTemplate messagingTemplate;
    private final RealtimeAccessEventMapper accessEventMapper;

    public RealtimePublisherService(SimpMessagingTemplate messagingTemplate, RealtimeAccessEventMapper accessEventMapper) {
        this.messagingTemplate = messagingTemplate;
        this.accessEventMapper = accessEventMapper;
    }

    public void publishAccessEvent(AccessEvent event) {
        messagingTemplate.convertAndSend(WebSocketConfig.ACCESS_EVENTS_TOPIC, accessEventMapper.toMessage(event));
    }

    public void publishDeviceStatus(Device device, String message) {
        messagingTemplate.convertAndSend(WebSocketConfig.DEVICE_STATUS_TOPIC, new RealtimeDeviceStatusMessage(
                device.getId(),
                device.getName(),
                device.getStatus(),
                device.getLastSeenAt(),
                device.getLastHeartbeatAt(),
                device.getCommunicationFailures(),
                message
        ));
    }

    public void publishSystemAlert(SystemAlertMessage alert) {
        messagingTemplate.convertAndSend(WebSocketConfig.SYSTEM_ALERTS_TOPIC, alert);
    }
}
