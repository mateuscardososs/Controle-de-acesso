package br.com.sport.accesscontrol.common.messaging;

import br.com.sport.accesscontrol.config.RabbitMqConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PreparedRabbitConsumers {

    private static final Logger log = LoggerFactory.getLogger(PreparedRabbitConsumers.class);

    @RabbitListener(queues = RabbitMqConfig.EMPLOYEE_SYNC_QUEUE)
    void consumeEmployeeSync(Map<String, Object> message) {
        log.info("employee_sync_message_received simulated=true payload={}", message);
    }

    @RabbitListener(queues = RabbitMqConfig.ACCESS_EVENT_QUEUE)
    void consumeAccessEvent(Map<String, Object> message) {
        log.info("access_event_message_received simulated=true payload={}", message);
    }

    @RabbitListener(queues = RabbitMqConfig.AUDIT_QUEUE)
    void consumeAudit(Map<String, Object> message) {
        log.info("audit_message_received simulated=true payload={}", message);
    }
}
