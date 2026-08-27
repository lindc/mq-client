package org.bear.mqclient.rabbitmq.service;

import com.rabbitmq.client.*;
import org.bear.mqclient.core.model.*;
import org.bear.mqclient.core.service.MQService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * RabbitMQ 服务实现。
 */
public class RabbitMQService implements MQService {

    private Connection connection;
    private Channel channel;
    private MQConnectionConfig config;
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, String> consumerTags = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    public CompletableFuture<Void> connect(MQConnectionConfig cfg) {
        this.config = cfg;
        return CompletableFuture.runAsync(() -> {
            try {
                ConnectionFactory factory = new ConnectionFactory();
                factory.setHost(cfg.getProperty("host"));
                String port = cfg.getProperty("port");
                if (port != null && !port.isEmpty()) {
                    factory.setPort(Integer.parseInt(port));
                }
                String vh = cfg.getProperty("virtualHost");
                factory.setVirtualHost(vh != null ? vh : "/");
                String user = cfg.getProperty("username");
                if (user != null && !user.isEmpty()) {
                    factory.setUsername(user);
                }
                String pass = cfg.getProperty("password");
                if (pass != null && !pass.isEmpty()) {
                    factory.setPassword(pass);
                }

                this.connection = factory.newConnection("mq-client");
                this.channel = connection.createChannel();
            } catch (Exception e) {
                throw new RuntimeException("RabbitMQ connection failed: " + e.getMessage(), e);
            }
        }, executor);
    }

    @Override
    public void disconnect() {
        subscriptions.clear();
        try {
            if (channel != null && channel.isOpen()) channel.close();
        } catch (Exception ignored) {}
        try {
            if (connection != null && connection.isOpen()) connection.close();
        } catch (Exception ignored) {}
        channel = null;
        connection = null;
    }

    @Override
    public boolean isConnected() {
        return connection != null && connection.isOpen();
    }

    @Override
    public MQConnectionConfig getConfig() { return config; }

    @Override
    public CompletableFuture<List<MQDestination>> listDestinations() {
        return CompletableFuture.supplyAsync(() -> {
            List<MQDestination> result = new ArrayList<>();
            try {
                // RabbitMQ Management API 可以列出所有队列和 Exchange
                // 这里使用 channel 的被动声明方式获取已知 destination
                // 实际项目中建议配合 Management HTTP API 获取完整列表
                result.add(new MQDestination("amq.default", MQDestination.Type.EXCHANGE));
                result.add(new MQDestination("amq.direct", MQDestination.Type.EXCHANGE));
                result.add(new MQDestination("amq.topic", MQDestination.Type.EXCHANGE));
                result.add(new MQDestination("amq.fanout", MQDestination.Type.EXCHANGE));
                result.add(new MQDestination("amq.headers", MQDestination.Type.EXCHANGE));
            } catch (Exception e) {
                throw new RuntimeException("Failed to list destinations", e);
            }
            return result;
        }, executor);
    }

    @Override
    public CompletableFuture<List<MQMessage>> browseMessages(String destination, int maxCount) {
        // RabbitMQ 没有原生浏览（非破坏性读），这里用 consume + nack 模拟
        return consumeMessages(destination, maxCount, 3000);
    }

    @Override
    public CompletableFuture<Void> sendMessage(String destination, MQMessage message) {
        return CompletableFuture.runAsync(() -> {
            try {
                byte[] body = message.getPayload();
                if (body == null) body = message.getPayloadText().getBytes(StandardCharsets.UTF_8);

                AMQP.BasicProperties.Builder props = new AMQP.BasicProperties.Builder();
                props.contentType(message.getContentType());
                if (message.getMessageId() != null) props.messageId(message.getMessageId());
                if (message.getCorrelationId() != null) props.correlationId(message.getCorrelationId());
                if (message.getReplyTo() != null) props.replyTo(message.getReplyTo());

                // headers
                if (!message.getHeaders().isEmpty()) {
                    Map<String, Object> headers = new HashMap<>(message.getHeaders());
                    props.headers(headers);
                }

                channel.basicPublish("", destination, props.build(), body);
            } catch (Exception e) {
                throw new RuntimeException("Send failed: " + e.getMessage(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<MQMessage>> consumeMessages(String destination, int maxCount, long timeoutMs) {
        return CompletableFuture.supplyAsync(() -> {
            List<MQMessage> messages = new ArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);

            try {
                com.rabbitmq.client.Consumer consumer = new DefaultConsumer(channel) {
                    int count = 0;

                    @Override
                    public void handleDelivery(String consumerTag, Envelope envelope,
                                               AMQP.BasicProperties properties, byte[] body) {
                        if (count >= maxCount) return;
                        count++;
                        messages.add(toMessage(envelope, properties, body));

                        if (count >= maxCount) {
                            latch.countDown();
                        }
                    }
                };

                String tag = channel.basicConsume(destination, true, consumer);
                latch.await(timeoutMs, TimeUnit.MILLISECONDS);
                channel.basicCancel(tag);

            } catch (Exception e) {
                throw new RuntimeException("Consume failed: " + e.getMessage(), e);
            }
            return messages;
        }, executor);
    }

    @Override
    public Subscription subscribe(String destination, Consumer<MQMessage> handler) {
        String subId = UUID.randomUUID().toString();
        Subscription sub = new Subscription(subId, destination);

        try {
            com.rabbitmq.client.Consumer consumer = new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String consumerTag, Envelope envelope,
                                           AMQP.BasicProperties properties, byte[] body) {
                    if (!sub.isActive()) return;
                    MQMessage msg = toMessage(envelope, properties, body);
                    handler.accept(msg);
                }
            };

            String tag = channel.basicConsume(destination, true, consumer);
            consumerTags.put(subId, tag);
            subscriptions.put(subId, sub);
        } catch (Exception e) {
            throw new RuntimeException("Subscribe failed: " + e.getMessage(), e);
        }

        return sub;
    }

    @Override
    public void unsubscribe(String subscriptionId) {
        Subscription sub = subscriptions.remove(subscriptionId);
        String tag = consumerTags.remove(subscriptionId);
        if (sub != null) sub.cancel();
        if (tag != null && channel != null && channel.isOpen()) {
            try { channel.basicCancel(tag); } catch (Exception ignored) {}
        }
    }

    // ── 消息转换 ──

    private MQMessage toMessage(Envelope envelope, AMQP.BasicProperties props, byte[] body) {
        MQMessage msg = new MQMessage();
        msg.setPayload(body);
        msg.setMessageId(props.getMessageId());
        msg.setContentType(props.getContentType());
        msg.setCorrelationId(props.getCorrelationId());
        msg.setReplyTo(props.getReplyTo());
        if (props.getTimestamp() != null) {
            msg.setTimestamp(props.getTimestamp().getTime());
        }

        // RabbitMQ 特有字段
        msg.setNativeField("exchange", envelope.getExchange());
        msg.setNativeField("routingKey", envelope.getRoutingKey());
        msg.setNativeField("deliveryTag", envelope.getDeliveryTag());
        msg.setNativeField("redeliver", envelope.isRedeliver());
        if (props.getDeliveryMode() != null)
            msg.setNativeField("deliveryMode", props.getDeliveryMode());
        if (props.getPriority() != null)
            msg.setNativeField("priority", props.getPriority());
        if (props.getExpiration() != null)
            msg.setNativeField("expiration", props.getExpiration());
        if (props.getHeaders() != null) {
            props.getHeaders().forEach((k, v) -> msg.setHeader(k, String.valueOf(v)));
        }

        return msg;
    }
}
