package org.bear.mqclient.kafka.service;

import org.bear.mqclient.core.model.*;
import org.bear.mqclient.core.service.MQService;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Kafka 服务实现。
 * <p>
 * Kafka 没有队列和传统浏览语义——browse 通过 seek + poll 模拟，
 * consume 通过 consumer group + poll 实现。
 */
public class KafkaService implements MQService {

    private MQConnectionConfig config;
    private AdminClient adminClient;
    private KafkaProducer<String, String> producer;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, KafkaConsumerRunner> activeSubscriptions = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Void> connect(MQConnectionConfig cfg) {
        this.config = cfg;
        return CompletableFuture.runAsync(() -> {
            try {
                Properties props = baseConsumerProps(UUID.randomUUID().toString());
                this.adminClient = AdminClient.create(props);
                // 验证连接
                adminClient.describeCluster().clusterId().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException("Kafka connect failed: " + e.getMessage(), e);
            }
        }, executor);
    }

    @Override
    public void disconnect() {
        // 停止所有订阅
        new ArrayList<>(activeSubscriptions.keySet()).forEach(this::unsubscribe);
        if (producer != null) {
            producer.close(Duration.ofSeconds(3));
            producer = null;
        }
        if (adminClient != null) {
            adminClient.close(Duration.ofSeconds(3));
            adminClient = null;
        }
    }

    @Override
    public boolean isConnected() { return adminClient != null; }

    @Override
    public MQConnectionConfig getConfig() { return config; }

    // ── 列出主题 ──

    @Override
    public CompletableFuture<List<MQDestination>> listDestinations() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Set<String> topics = adminClient.listTopics().names().get(10, TimeUnit.SECONDS);
                return topics.stream()
                        .filter(t -> !t.startsWith("_")) // 过滤内部主题
                        .map(t -> new MQDestination(t, MQDestination.Type.TOPIC))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                throw new RuntimeException("Failed to list topics: " + e.getMessage(), e);
            }
        }, executor);
    }

    // ── 浏览消息（seek + poll） ──

    @Override
    public CompletableFuture<List<MQMessage>> browseMessages(String topic, int maxCount) {
        return CompletableFuture.supplyAsync(() -> {
            String groupId = "mq-client-browse-" + UUID.randomUUID().toString().substring(0, 8);
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(baseConsumerProps(groupId))) {
                List<PartitionInfo> partitions = consumer.partitionsFor(topic, Duration.ofSeconds(5));
                if (partitions == null || partitions.isEmpty()) return List.of();

                List<TopicPartition> tps = partitions.stream()
                        .map(p -> new TopicPartition(topic, p.partition()))
                        .collect(Collectors.toList());

                consumer.assign(tps);
                consumer.seekToBeginning(tps);

                List<MQMessage> results = new ArrayList<>();
                long deadline = System.currentTimeMillis() + 5000;
                int totalPolled = 0;

                while (totalPolled < maxCount && System.currentTimeMillis() < deadline) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                    for (ConsumerRecord<String, String> rec : records) {
                        if (totalPolled >= maxCount) break;
                        results.add(toCoreMessage(rec, topic));
                        totalPolled++;
                    }
                }
                return results;
            } catch (Exception e) {
                throw new RuntimeException("Browse failed: " + e.getMessage(), e);
            }
        }, executor);
    }

    // ── 消费消息 ──

    @Override
    public CompletableFuture<List<MQMessage>> consumeMessages(String topic, int maxCount, long timeoutMs) {
        return CompletableFuture.supplyAsync(() -> {
            String groupId = config.getProperty("groupId");
            if (groupId == null || groupId.isEmpty()) groupId = "mq-client-consume-" + UUID.randomUUID();
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(baseConsumerProps(groupId))) {
                consumer.subscribe(List.of(topic));
                List<MQMessage> results = new ArrayList<>();
                long deadline = System.currentTimeMillis() + timeoutMs;

                while (results.size() < maxCount && System.currentTimeMillis() < deadline) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                    for (ConsumerRecord<String, String> rec : records) {
                        if (results.size() >= maxCount) break;
                        results.add(toCoreMessage(rec, topic));
                    }
                }
                consumer.commitSync(Duration.ofSeconds(3));
                return results;
            } catch (Exception e) {
                throw new RuntimeException("Consume failed: " + e.getMessage(), e);
            }
        }, executor);
    }

    // ── 发送消息 ──

    @Override
    public CompletableFuture<Void> sendMessage(String topic, MQMessage message) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (producer == null) {
                    Properties props = new Properties();
                    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getProperty("bootstrapServers"));
                    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                    props.put(ProducerConfig.ACKS_CONFIG, "all");
                    String secProto = config.getProperty("securityProtocol");
                    if (secProto != null && !secProto.isEmpty()) {
                        props.put("security.protocol", secProto);
                    }
                    this.producer = new KafkaProducer<>(props);
                }

                String keyStr = message.getNativeField("kafkaKey");
                String valueStr = message.getPayloadText();

                ProducerRecord<String, String> record = new ProducerRecord<>(topic, keyStr, valueStr);

                // 添加 headers
                for (Map.Entry<String, String> h : message.getHeaders().entrySet()) {
                    record.headers().add(h.getKey(), h.getValue().getBytes(StandardCharsets.UTF_8));
                }

                producer.send(record).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException("Send failed: " + e.getMessage(), e);
            }
        }, executor);
    }

    // ── 订阅 ──

    @Override
    public Subscription subscribe(String topic, Consumer<MQMessage> handler) {
        String subId = UUID.randomUUID().toString();
        String groupId = config.getProperty("groupId");
        if (groupId == null || groupId.isEmpty()) groupId = "mq-client-sub-" + subId.substring(0, 8);

        KafkaConsumerRunner runner = new KafkaConsumerRunner(topic, groupId, handler);
        Thread thread = new Thread(runner, "kafka-sub-" + topic);
        thread.setDaemon(true);
        thread.start();

        Subscription sub = new Subscription(subId, topic);
        activeSubscriptions.put(subId, runner);
        return sub;
    }

    @Override
    public void unsubscribe(String subscriptionId) {
        KafkaConsumerRunner runner = activeSubscriptions.remove(subscriptionId);
        if (runner != null) {
            runner.shutdown();
        }
    }

    // ── 内部：订阅运行器 ──

    private class KafkaConsumerRunner implements Runnable {
        private final String topic;
        private final String groupId;
        private final Consumer<MQMessage> handler;
        private volatile boolean running = true;
        private KafkaConsumer<String, String> consumer;

        KafkaConsumerRunner(String topic, String groupId, Consumer<MQMessage> handler) {
            this.topic = topic;
            this.groupId = groupId;
            this.handler = handler;
        }

        @Override
        public void run() {
            try {
                this.consumer = new KafkaConsumer<>(baseConsumerProps(groupId));
                consumer.subscribe(List.of(topic));
                while (running) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    for (ConsumerRecord<String, String> rec : records) {
                        if (!running) break;
                        MQMessage msg = toCoreMessage(rec, topic);
                        handler.accept(msg);
                    }
                    if (!records.isEmpty()) {
                        consumer.commitSync(Duration.ofSeconds(3));
                    }
                }
            } catch (Exception e) {
                if (running) {
                    System.err.println("[Kafka] Subscribe error: " + e.getMessage());
                }
            } finally {
                if (consumer != null) {
                    consumer.close(Duration.ofSeconds(2));
                }
            }
        }

        void shutdown() { this.running = false; }
    }

    // ── Kafka ConsumerRecord → Core MQMessage ──

    private MQMessage toCoreMessage(ConsumerRecord<String, String> rec, String topic) {
        MQMessage msg = new MQMessage();
        // 用 offset + partition 构造唯一 ID
        msg.setMessageId(topic + "-" + rec.partition() + "-" + rec.offset());
        msg.setPayloadText(rec.value());
        msg.setContentType("text/plain");
        msg.setTimestamp(rec.timestamp());

        // Kafka 特有字段
        msg.setNativeField("topic", topic);
        msg.setNativeField("partition", rec.partition());
        msg.setNativeField("offset", rec.offset());
        msg.setNativeField("kafkaKey", rec.key());
        msg.setNativeField("timestampType", rec.timestampType().name);
        msg.setNativeField("serializedKeySize", rec.serializedKeySize());
        msg.setNativeField("serializedValueSize", rec.serializedValueSize());
        msg.setNativeField("leaderEpoch", rec.leaderEpoch().orElse(-1));

        // Headers
        if (rec.headers() != null) {
            Map<String, String> headerMap = new LinkedHashMap<>();
            for (Header h : rec.headers()) {
                String val = h.value() != null ? new String(h.value(), StandardCharsets.UTF_8) : "";
                headerMap.put(h.key(), val);
                msg.setHeader(h.key(), val);
            }
            msg.setNativeField("headerCount", rec.headers().toArray().length);
        }

        return msg;
    }

    // ── 基础 Kafka Consumer 属性 ──

    private Properties baseConsumerProps(String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getProperty("bootstrapServers"));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "100");

        String secProto = config.getProperty("securityProtocol");
        if (secProto != null && !secProto.isEmpty()) {
            props.put("security.protocol", secProto);
        }
        String saslMech = config.getProperty("saslMechanism");
        if (saslMech != null && !saslMech.isEmpty()) {
            props.put("sasl.mechanism", saslMech);
            String jaas = config.getProperty("saslJaasConfig");
            if (jaas != null && !jaas.isEmpty()) {
                props.put("sasl.jaas.config", jaas);
            }
        }

        return props;
    }
}
