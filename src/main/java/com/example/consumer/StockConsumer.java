package com.example.consumer;

import com.example.service.StockTxService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class StockConsumer {

    @Autowired
    private StockTxService stockTxService;

    @RabbitListener(queues = "ticket.stock.queue", ackMode = "MANUAL")
    public void handleStockUpdate(Map<String, Object> msg, Channel channel, Message message) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String messageId = (String) msg.get("messageId");

        try {
            stockTxService.handleStockUpdateTx(msg);

            channel.basicAck(deliveryTag, false);
            log.info("消息消费成功并确认: {}", messageId);

        } catch (Exception e) {
            log.error("消息消费失败: {}", messageId, e);

            // 这里建议是否 requeue 取决于你的重试策略
            // true: 重新入队，可能重复投递
            // false: 丢给死信队列（前提是已配置 DLX）
            channel.basicNack(deliveryTag, false, false);
        }
    }
}