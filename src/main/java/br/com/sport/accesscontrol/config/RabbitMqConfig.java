package br.com.sport.accesscontrol.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@EnableRabbit
@Configuration
public class RabbitMqConfig {

    public static final String ACCESS_EVENTS_EXCHANGE = "access.events";
    public static final String INTEGRATION_EVENTS_EXCHANGE = "integration.events";
    public static final String AUDIT_EVENTS_EXCHANGE = "audit.events";
    public static final String DLX = "access-control.dlx";

    public static final String EMPLOYEE_SYNC_QUEUE = "employee.sync.queue";
    public static final String ACCESS_EVENT_QUEUE = "access.event.queue";
    public static final String AUDIT_QUEUE = "audit.queue";

    @Bean
    public TopicExchange accessEventsExchange() {
        return ExchangeBuilder.topicExchange(ACCESS_EVENTS_EXCHANGE).durable(true).build();
    }

    @Bean
    public TopicExchange integrationEventsExchange() {
        return ExchangeBuilder.topicExchange(INTEGRATION_EVENTS_EXCHANGE).durable(true).build();
    }

    @Bean
    public TopicExchange auditEventsExchange() {
        return ExchangeBuilder.topicExchange(AUDIT_EVENTS_EXCHANGE).durable(true).build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(DLX).durable(true).build();
    }

    @Bean
    public Queue employeeSyncQueue() {
        return queue(EMPLOYEE_SYNC_QUEUE);
    }

    @Bean
    public Queue accessEventQueue() {
        return queue(ACCESS_EVENT_QUEUE);
    }

    @Bean
    public Queue auditQueue() {
        return queue(AUDIT_QUEUE);
    }

    @Bean
    public Queue employeeSyncDlq() {
        return QueueBuilder.durable(EMPLOYEE_SYNC_QUEUE + ".dlq").build();
    }

    @Bean
    public Queue accessEventDlq() {
        return QueueBuilder.durable(ACCESS_EVENT_QUEUE + ".dlq").build();
    }

    @Bean
    public Queue auditDlq() {
        return QueueBuilder.durable(AUDIT_QUEUE + ".dlq").build();
    }

    @Bean
    public Binding employeeSyncBinding(Queue employeeSyncQueue, TopicExchange integrationEventsExchange) {
        return BindingBuilder.bind(employeeSyncQueue).to(integrationEventsExchange).with("employee.*");
    }

    @Bean
    public Binding accessEventBinding(Queue accessEventQueue, TopicExchange accessEventsExchange) {
        return BindingBuilder.bind(accessEventQueue).to(accessEventsExchange).with("access.event.*");
    }

    @Bean
    public Binding auditBinding(Queue auditQueue, TopicExchange auditEventsExchange) {
        return BindingBuilder.bind(auditQueue).to(auditEventsExchange).with("audit.*");
    }

    @Bean
    public Binding employeeSyncDlqBinding(Queue employeeSyncDlq, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(employeeSyncDlq).to(deadLetterExchange).with(EMPLOYEE_SYNC_QUEUE + ".dlq");
    }

    @Bean
    public Binding accessEventDlqBinding(Queue accessEventDlq, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(accessEventDlq).to(deadLetterExchange).with(ACCESS_EVENT_QUEUE + ".dlq");
    }

    @Bean
    public Binding auditDlqBinding(Queue auditDlq, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(auditDlq).to(deadLetterExchange).with(AUDIT_QUEUE + ".dlq");
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    private Queue queue(String name) {
        return QueueBuilder.durable(name)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", name + ".dlq")
                .build();
    }
}
