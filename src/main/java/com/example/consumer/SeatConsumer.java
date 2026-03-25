package com.example.consumer;

import com.example.service.SeatTxService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class SeatConsumer {

    @Autowired
    private SeatTxService seatTxService;

    @RabbitListener(queues = "ticket.book.queue", ackMode = "MANUAL")
    public void handleBooking(Map<String, Object> msg, Channel channel, Message message) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        Object orderId = msg.get("orderId");

        try {
            // 监听器仅负责编排：调用事务服务，事务提交成功后再 ACK
            seatTxService.handleBookingTx(msg);
            channel.basicAck(deliveryTag, false);
            log.info("订票消息消费成功: orderId={}", orderId);
        } catch (Exception e) {
            log.error("订票消息消费失败: orderId={}", orderId, e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
