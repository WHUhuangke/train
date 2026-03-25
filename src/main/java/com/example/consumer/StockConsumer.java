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
        Object messageId = msg.get("messageId");

        try {
            // 监听器仅负责编排：调用事务服务，事务提交成功后再 ACK
            stockTxService.handleStockUpdateTx(msg);
            channel.basicAck(deliveryTag, false);
            log.info("库存消息消费成功: messageId={}", messageId);
        } catch (Exception e) {
            log.error("库存消息消费失败: messageId={}", messageId, e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
