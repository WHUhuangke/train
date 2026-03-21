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
            seatTxService.handleBookingTx(msg);
            channel.basicAck(deliveryTag, false);
            log.info("订票消息消费成功: orderId={}", orderId);
        } catch (Exception e) {
            log.error("订票消息消费失败: orderId={}", orderId, e);

            // 建议结合死信队列策略决定是否 requeue
            channel.basicNack(deliveryTag, false, false);
        }
    }
}