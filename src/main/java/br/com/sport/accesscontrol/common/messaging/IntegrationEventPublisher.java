package br.com.sport.accesscontrol.common.messaging;

import br.com.sport.accesscontrol.config.RabbitMqConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
public class IntegrationEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(IntegrationEventPublisher.class);
    private final RabbitTemplate rabbitTemplate;

    public IntegrationEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishEmployeeSync(Object event) {
        publish(RabbitMqConfig.INTEGRATION_EVENTS_EXCHANGE, "employee.sync", event);
    }

    public void publishIntelbrasSync(Object event) {
        publish(RabbitMqConfig.INTEGRATION_EVENTS_EXCHANGE, "intelbras.sync.requested", event);
    }

    public void publishAccessEvent(Object event) {
        publish(RabbitMqConfig.ACCESS_EVENTS_EXCHANGE, "access.event.received", event);
    }

    public void publishAudit(Object event) {
        publish(RabbitMqConfig.AUDIT_EVENTS_EXCHANGE, "audit.created", event);
    }

    private void publish(String exchange, String routingKey, Object event) {
        var envelope = Map.of("event", event, "publishedAt", Instant.now().toString());
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, envelope);
            log.info("rabbit_event_published exchange={} routing_key={}", exchange, routingKey);
        } catch (AmqpException exception) {
            log.warn("rabbit_event_publish_skipped exchange={} routing_key={} reason={}",
                    exchange, routingKey, exception.getMessage());
        }
    }
}
