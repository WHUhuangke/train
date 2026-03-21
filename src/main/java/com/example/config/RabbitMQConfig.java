// RabbitMQConfig.java
package com.example.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 交换机、队列、路由键配置。
 */
@Configuration
public class RabbitMQConfig {

    public static final String TICKET_EXCHANGE = "ticket.exchange";

    public static final String BOOK_QUEUE = "ticket.book.queue";
    public static final String STOCK_QUEUE = "ticket.stock.queue";
    public static final String ORDER_NOTIFY_QUEUE = "ticket.order.notify.queue";

    public static final String BOOK_ROUTING_KEY = "ticket.book";
    public static final String STOCK_ROUTING_KEY = "ticket.stock";
    public static final String ORDER_NOTIFY_ROUTING_KEY = "ticket.order.notify";

    @Bean
    public TopicExchange ticketExchange() {
        return new TopicExchange(TICKET_EXCHANGE, true, false);
    }

    @Bean
    public Queue bookQueue() {
        return new Queue(BOOK_QUEUE, true);
    }

    @Bean
    public Queue stockQueue() {
        return new Queue(STOCK_QUEUE, true);
    }

    /**
     * 订单完成通知队列：消费者用于推送 SSE 通知。
     */
    @Bean
    public Queue orderNotifyQueue() {
        return new Queue(ORDER_NOTIFY_QUEUE, true);
    }

    @Bean
    public Binding bookBinding(Queue bookQueue, TopicExchange ticketExchange) {
        return BindingBuilder.bind(bookQueue).to(ticketExchange).with(BOOK_ROUTING_KEY);
    }

    @Bean
    public Binding stockBinding(Queue stockQueue, TopicExchange ticketExchange) {
        return BindingBuilder.bind(stockQueue).to(ticketExchange).with(STOCK_ROUTING_KEY);
    }

    @Bean
    public Binding orderNotifyBinding(Queue orderNotifyQueue, TopicExchange ticketExchange) {
        return BindingBuilder.bind(orderNotifyQueue).to(ticketExchange).with(ORDER_NOTIFY_ROUTING_KEY);
    }
}
