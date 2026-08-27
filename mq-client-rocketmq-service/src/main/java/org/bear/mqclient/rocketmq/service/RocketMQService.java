package org.bear.mqclient.rocketmq.service;

import org.bear.mqclient.core.model.*;
import org.bear.mqclient.core.service.MQService;
import org.apache.rocketmq.client.consumer.*;
import org.apache.rocketmq.client.consumer.listener.*;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.*;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * RocketMQ 服务实现。
 */
public class RocketMQService implements MQService {

    private MQConnectionConfig config;
    private DefaultMQProducer producer;
    private DefaultMQAdminExt admin;
    private final Map<String, PushSubscription> subscriptions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    public CompletableFuture<Void> connect(MQConnectionConfig cfg) {
        this.config = cfg;
        return CompletableFuture.runAsync(() -> {
            try {
                String nsAddr = cfg.getProperty("namesrvAddr");

                // Producer
                String pg = cfg.getProperty("producerGroup");
                this.producer = new DefaultMQProducer(
                        pg != null && !pg.isEmpty() ? pg : "mq-client-producer");
                producer.setNamesrvAddr(nsAddr);
                producer.setSendMsgTimeout(10000);
                producer.start();

                // Admin (topic listing etc.)
                this.admin = new DefaultMQAdminExt();
                admin.setNamesrvAddr(nsAddr);
                admin.start();
            } catch (Exception e) {
                throw new RuntimeException("RocketMQ connect failed: " + e.getMessage(), e);
            }
        }, executor);
    }

    @Override
    public void disconnect() {
        subscriptions.values().forEach(PushSubscription::shutdown);
        subscriptions.clear();
        if (producer != null) { producer.shutdown(); producer = null; }
        if (admin != null) { admin.shutdown(); admin = null; }
    }

    @Override
    public boolean isConnected() { return admin != null; }

    @Override
    public MQConnectionConfig getConfig() { return config; }

    // ── 列出 Topic ──

    @Override
    public CompletableFuture<List<MQDestination>> listDestinations() {
        return CompletableFuture.completedFuture(List.of());
    }

    // ── 浏览消息（使用 LitePullConsumer） ──

    @Override
    public CompletableFuture<List<MQMessage>> browseMessages(String topic, int maxCount) {
        return CompletableFuture.supplyAsync(() -> {
            DefaultLitePullConsumer consumer = null;
            try {
                String cg = "mq-client-browse-" + UUID.randomUUID().toString().substring(0, 8);
                consumer = new DefaultLitePullConsumer(cg);
                consumer.setNamesrvAddr(config.getProperty("namesrvAddr"));
                consumer.subscribe(topic, "*");
                consumer.setPullBatchSize(maxCount);
                consumer.start();

                List<MQMessage> results = new ArrayList<>();
                long deadline = System.currentTimeMillis() + 5000;

                while (results.size() < maxCount && System.currentTimeMillis() < deadline) {
                    List<MessageExt> msgs = consumer.poll(1000);
                    if (msgs == null || msgs.isEmpty()) continue;
                    for (MessageExt ext : msgs) {
                        if (results.size() >= maxCount) break;
                        results.add(toCoreMessage(ext, topic));
                    }
                }
                return results;
            } catch (Exception e) {
                throw new RuntimeException("Browse failed: " + e.getMessage(), e);
            } finally {
                if (consumer != null) consumer.shutdown();
            }
        }, executor);
    }

    // ── 发送消息 ──

    @Override
    public CompletableFuture<Void> sendMessage(String topic, MQMessage message) {
        return CompletableFuture.runAsync(() -> {
            try {
                byte[] body = message.getPayload();
                if (body == null) body = message.getPayloadText().getBytes(StandardCharsets.UTF_8);

                Message rocketMsg = new Message();
                rocketMsg.setTopic(topic);
                rocketMsg.setBody(body);

                // Tag
                String tag = message.getNativeField("rocketTag");
                if (tag != null) rocketMsg.setTags(tag);

                // Key
                String key = message.getNativeField("rocketKey");
                if (key != null) rocketMsg.setKeys(key);

                // Headers / user properties
                for (var e : message.getHeaders().entrySet()) {
                    rocketMsg.putUserProperty(e.getKey(), e.getValue());
                }
                String delayStr = message.getNativeField("delayLevel");
                if (delayStr != null) {
                    rocketMsg.setDelayTimeLevel(Integer.parseInt(delayStr.toString()));
                }

                producer.send(rocketMsg);
            } catch (Exception e) {
                throw new RuntimeException("Send failed: " + e.getMessage(), e);
            }
        }, executor);
    }

    // ── 消费消息 ──

    @Override
    public CompletableFuture<List<MQMessage>> consumeMessages(String topic, int maxCount, long timeoutMs) {
        return CompletableFuture.supplyAsync(() -> {
            DefaultLitePullConsumer consumer = null;
            try {
                String cg = config.getProperty("consumerGroup");
                if (cg == null || cg.isEmpty()) cg = "mq-client-consume-" + UUID.randomUUID();
                consumer = new DefaultLitePullConsumer(cg);
                consumer.setNamesrvAddr(config.getProperty("namesrvAddr"));
                consumer.subscribe(topic, "*");
                consumer.setPullBatchSize(maxCount);
                consumer.start();

                List<MQMessage> results = new ArrayList<>();
                long deadline = System.currentTimeMillis() + timeoutMs;

                while (results.size() < maxCount && System.currentTimeMillis() < deadline) {
                    List<MessageExt> msgs = consumer.poll(1000);
                    if (msgs == null || msgs.isEmpty()) continue;
                    for (MessageExt ext : msgs) {
                        if (results.size() >= maxCount) break;
                        results.add(toCoreMessage(ext, topic));
                    }
                }
                return results;
            } catch (Exception e) {
                throw new RuntimeException("Consume failed: " + e.getMessage(), e);
            } finally {
                if (consumer != null) consumer.shutdown();
            }
        }, executor);
    }

    // ── 订阅 ──

    @Override
    public Subscription subscribe(String topic, Consumer<MQMessage> handler) {
        String subId = UUID.randomUUID().toString();
        String cg = config.getProperty("consumerGroup");
        if (cg == null || cg.isEmpty()) cg = "mq-client-sub-" + subId.substring(0, 8);

        DefaultMQPushConsumer pushConsumer = new DefaultMQPushConsumer(cg);
        pushConsumer.setNamesrvAddr(config.getProperty("namesrvAddr"));

        try {
            pushConsumer.subscribe(topic, "*");
            pushConsumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
                for (MessageExt ext : msgs) {
                    MQMessage msg = toCoreMessage(ext, topic);
                    handler.accept(msg);
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            pushConsumer.start();
        } catch (Exception e) {
            throw new RuntimeException("Subscribe failed: " + e.getMessage(), e);
        }

        Subscription sub = new Subscription(subId, topic);
        subscriptions.put(subId, new PushSubscription(sub, pushConsumer));
        return sub;
    }

    @Override
    public void unsubscribe(String subscriptionId) {
        PushSubscription ps = subscriptions.remove(subscriptionId);
        if (ps != null) ps.shutdown();
    }

    // ── 内部 ──

    private record PushSubscription(Subscription sub, DefaultMQPushConsumer consumer) {
        void shutdown() {
            sub.cancel();
            consumer.shutdown();
        }
    }

    // ── MessageExt → Core MQMessage ──

    private MQMessage toCoreMessage(MessageExt ext, String topic) {
        MQMessage msg = new MQMessage();
        msg.setMessageId(ext.getMsgId());
        msg.setPayload(ext.getBody());
        msg.setTimestamp(ext.getBornTimestamp());

        // 通用字段
        msg.setNativeField("topic", topic);
        msg.setNativeField("RocketMQ.msgId", ext.getMsgId());
        msg.setNativeField("RocketMQ.queueId", ext.getQueueId());
        msg.setNativeField("RocketMQ.queueOffset", ext.getQueueOffset());
        msg.setNativeField("RocketMQ.commitLogOffset", ext.getCommitLogOffset());
        msg.setNativeField("RocketMQ.bornTimestamp", ext.getBornTimestamp());
        msg.setNativeField("RocketMQ.storeTimestamp", ext.getStoreTimestamp());
        msg.setNativeField("RocketMQ.bornHost", ext.getBornHost() != null ? ext.getBornHost().toString() : "");
        msg.setNativeField("RocketMQ.storeHost", ext.getStoreHost() != null ? ext.getStoreHost().toString() : "");
        msg.setNativeField("RocketMQ.reconsumeTimes", ext.getReconsumeTimes());
        msg.setNativeField("RocketMQ.sysFlag", ext.getSysFlag());

        // Tag / Key
        String tags = ext.getTags();
        if (tags != null) {
            msg.setNativeField("RocketMQ.tags", tags);
            msg.setHeader("TAGS", tags);
        }
        String keys = ext.getKeys();
        if (keys != null) {
            msg.setNativeField("RocketMQ.keys", keys);
        }

        // Properties
        for (var e : ext.getProperties().entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (v != null) {
                msg.setHeader(k, v);
                msg.setUserProperty(k, v);
            }
        }

        return msg;
    }
}
