package org.bear.mqclient.artemis.service;

import org.bear.mqclient.core.model.*;
import org.bear.mqclient.core.service.MQService;
import org.bear.mqclient.core.util.LogAdapter;
import org.apache.activemq.artemis.api.core.*;
import org.apache.activemq.artemis.api.core.client.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Artemis 服务实现 — 使用 Core API。
 */
public class ArtemisService implements MQService {

    private MQConnectionConfig config;
    private ServerLocator locator;
    private ClientSessionFactory factory;
    private ClientSession session;
    private final Map<String, ActiveSubscription> subscriptions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    public CompletableFuture<Void> connect(MQConnectionConfig cfg) {
        this.config = cfg;
        return CompletableFuture.runAsync(() -> {
            try {
                String host = cfg.getProperty("host");
                String portStr = cfg.getProperty("port");
                String user = cfg.getProperty("username");
                String pass = cfg.getProperty("password");

                String uri = "tcp://" + (host != null ? host : "localhost")
                        + ":" + (portStr != null ? portStr : "61616");

                this.locator = ActiveMQClient.createServerLocator(uri);
                this.factory = locator.createSessionFactory();
                this.session = factory.createSession(
                        user != null ? user : "artemis",
                        pass != null ? pass : "artemis",
                        false, true, true, true, 0);
            } catch (Exception e) {
                throw new RuntimeException("Artemis connect failed: " + e.getMessage(), e);
            }
        }, executor);
    }

    @Override
    public void disconnect() {
        subscriptions.values().forEach(ActiveSubscription::cancel);
        subscriptions.clear();
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (factory != null) factory.close(); } catch (Exception ignored) {}
        try { if (locator != null) locator.close(); } catch (Exception ignored) {}
        session = null; factory = null; locator = null;
    }

    @Override
    public boolean isConnected() { return session != null && !session.isClosed(); }

    @Override
    public MQConnectionConfig getConfig() { return config; }

    @Override
    public CompletableFuture<List<MQDestination>> listDestinations() {
        return CompletableFuture.completedFuture(List.of());
    }

    // ── 浏览消息（browseOnly=true） ──

    @Override
    public CompletableFuture<List<MQMessage>> browseMessages(String destination, int maxCount) {
        return CompletableFuture.supplyAsync(() -> {
            ClientConsumer consumer = null;
            try {
                consumer = session.createConsumer(destination, true);
                List<MQMessage> results = new ArrayList<>();
                for (int i = 0; i < maxCount; i++) {
                    ClientMessage artMsg = consumer.receive(2000);
                    if (artMsg == null) break;
                    results.add(toCoreMessage(artMsg, destination));
                }
                return results;
            } catch (Exception e) {
                throw new RuntimeException("Browse failed: " + e.getMessage(), e);
            } finally {
                if (consumer != null) try { consumer.close(); } catch (Exception ignored) {}
            }
        }, executor);
    }

    // ── 发送消息 ──

    @Override
    public CompletableFuture<Void> sendMessage(String destination, MQMessage message) {
        return CompletableFuture.runAsync(() -> {
            try {
                ClientMessage artMsg = session.createMessage(true);
                if (message.getCorrelationId() != null)
                    artMsg.putStringProperty("correlationId", message.getCorrelationId());
                if (message.getReplyTo() != null)
                    artMsg.putStringProperty("replyTo", message.getReplyTo());
                if (message.getContentType() != null)
                    artMsg.putStringProperty("contentType", message.getContentType());
                for (var e : message.getHeaders().entrySet())
                    artMsg.putStringProperty(e.getKey(), e.getValue());

                byte[] payload = message.getPayload();
                if (payload == null) payload = message.getPayloadText().getBytes(StandardCharsets.UTF_8);
                artMsg.getBodyBuffer().writeBytes(payload);

                ClientProducer producer = session.createProducer(destination);
                producer.send(artMsg);
                producer.close();
            } catch (Exception e) {
                throw new RuntimeException("Send failed: " + e.getMessage(), e);
            }
        }, executor);
    }

    // ── 消费消息 ──

    @Override
    public CompletableFuture<List<MQMessage>> consumeMessages(String destination, int maxCount, long timeoutMs) {
        return CompletableFuture.supplyAsync(() -> {
            ClientConsumer consumer = null;
            try {
                consumer = session.createConsumer(destination);
                List<MQMessage> results = new ArrayList<>();
                long deadline = System.currentTimeMillis() + timeoutMs;
                for (int i = 0; i < maxCount; i++) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    ClientMessage artMsg = consumer.receive(Math.min(remaining, 5000));
                    if (artMsg == null) break;
                    results.add(toCoreMessage(artMsg, destination));
                    artMsg.acknowledge();
                }
                return results;
            } catch (Exception e) {
                throw new RuntimeException("Consume failed: " + e.getMessage(), e);
            } finally {
                if (consumer != null) try { consumer.close(); } catch (Exception ignored) {}
            }
        }, executor);
    }

    // ── 订阅 ──

    @Override
    public Subscription subscribe(String destination, Consumer<MQMessage> handler) {
        String subId = UUID.randomUUID().toString();
        Subscription sub = new Subscription(subId, destination);
        ActiveSubscription activeSub = new ActiveSubscription(subId, destination, handler);
        Thread t = new Thread(activeSub, "artemis-sub-" + destination);
        t.setDaemon(true);
        t.start();
        subscriptions.put(subId, activeSub);
        return sub;
    }

    @Override
    public void unsubscribe(String subscriptionId) {
        ActiveSubscription activeSub = subscriptions.remove(subscriptionId);
        if (activeSub != null) activeSub.cancel();
    }

    // ── 内部 ──

    private class ActiveSubscription implements Runnable {
        private final String subId;
        private final String destination;
        private final Consumer<MQMessage> handler;
        private volatile boolean running = true;

        ActiveSubscription(String subId, String dest, Consumer<MQMessage> h) {
            this.subId = subId; this.destination = dest; this.handler = h;
        }

        @Override
        public void run() {
            ClientConsumer consumer = null;
            try {
                consumer = session.createConsumer(destination);
                while (running) {
                    ClientMessage artMsg = consumer.receive(3000);
                    if (artMsg == null) continue;
                    if (!running) break;
                    handler.accept(toCoreMessage(artMsg, destination));
                    artMsg.acknowledge();
                }
            } catch (Exception e) {
                if (running) LogAdapter.error("ARTEMIS", "Subscribe error", e);
            } finally {
                if (consumer != null) try { consumer.close(); } catch (Exception ignored) {}
            }
        }
        void cancel() { this.running = false; }
    }

    // ── 消息转换 ──

    private MQMessage toCoreMessage(ClientMessage artMsg, String destination) throws Exception {
        MQMessage msg = new MQMessage();
        msg.setMessageId(String.valueOf(artMsg.getMessageID()));
        msg.setTimestamp(artMsg.getTimestamp());

        int bodySize = artMsg.getBodySize();
        if (bodySize > 0) {
            byte[] body = new byte[bodySize];
            artMsg.getBodyBuffer().readBytes(body);
            msg.setPayload(body);
        } else {
            msg.setPayload(new byte[0]);
        }

        String contentType = artMsg.getStringProperty("contentType");
        if (contentType != null) msg.setContentType(contentType);
        String corrId = artMsg.getStringProperty("correlationId");
        if (corrId != null) msg.setCorrelationId(corrId);
        String replyTo = artMsg.getStringProperty("replyTo");
        if (replyTo != null) msg.setReplyTo(replyTo);

        for (SimpleString key : artMsg.getPropertyNames()) {
            String k = key.toString();
            if ("messageId".equals(k) || "correlationId".equals(k)
                    || "replyTo".equals(k) || "contentType".equals(k)) continue;
            Object v = artMsg.getObjectProperty(key);
            if (v instanceof String s) msg.setHeader(k, s);
            msg.setUserProperty(k, v);
        }

        msg.setNativeField("destination", destination);
        msg.setNativeField("Artemis.messageId", artMsg.getMessageID());
        msg.setNativeField("Artemis.timestamp", artMsg.getTimestamp());
        msg.setNativeField("Artemis.priority", artMsg.getPriority());
        msg.setNativeField("Artemis.expiration", artMsg.getExpiration());
        msg.setNativeField("Artemis.durable", artMsg.isDurable());
        msg.setNativeField("Artemis.largeMessage", artMsg.isLargeMessage());
        msg.setNativeField("Artemis.type", artMsg.getType());
        msg.setNativeField("Artemis.address",
                artMsg.getAddress() != null ? artMsg.getAddress().toString() : "");

        return msg;
    }
}
