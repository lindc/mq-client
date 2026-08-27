package org.bear.mqclient.ibm.service;

import com.ibm.mq.MQException;
import com.ibm.mq.constants.MQConstants;
import org.bear.mqclient.core.model.*;
import org.bear.mqclient.core.service.MQService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * IBM MQ 服务实现 — 使用原生 MQI API 获取完整 MQMD 访问。
 * <p>
 * 注意：IBM MQ 的 com.ibm.mq.MQMessage / MQDestination 与 core 模型类同名，
 * 代码中 core 类通过 import 引入，IBM MQ 类使用完全限定名。
 */
public class IBMMQService implements MQService {

    private com.ibm.mq.MQQueueManager qmgr;
    private MQConnectionConfig config;
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, com.ibm.mq.MQQueue> subscriptionQueues = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    public CompletableFuture<Void> connect(MQConnectionConfig cfg) {
        this.config = cfg;
        return CompletableFuture.runAsync(() -> {
            try {
                String host = cfg.getProperty("host");
                String portStr = cfg.getProperty("port");
                String channel = cfg.getProperty("channel");
                String qmgrName = cfg.getProperty("queueManager");
                String user = cfg.getProperty("username");
                String pass = cfg.getProperty("password");
                String sslCipher = cfg.getProperty("sslCipherSuite");

                Hashtable<String, Object> props = new Hashtable<>();
                props.put("hostname", host != null ? host : "localhost");
                props.put("port", portStr != null ? Integer.parseInt(portStr) : 1414);
                props.put("channel", channel != null ? channel : "SYSTEM.DEF.SVRCONN");
                props.put("userID", user != null && !user.isEmpty() ? user : "mqm");
                if (pass != null && !pass.isEmpty()) {
                    props.put("password", pass);
                }
                props.put(MQConstants.USE_MQCSP_AUTHENTICATION_PROPERTY, true);
                props.put(MQConstants.CCSID_PROPERTY, 1208); // UTF-8

                if (sslCipher != null && !sslCipher.isEmpty()) {
                    props.put(MQConstants.SSL_CIPHER_SUITE_PROPERTY, sslCipher);
                }

                this.qmgr = new com.ibm.mq.MQQueueManager(qmgrName, props);
            } catch (Exception e) {
                throw new RuntimeException("IBM MQ connect failed: " + e.getMessage(), e);
            }
        }, executor);
    }

    @Override
    public void disconnect() {
        subscriptions.clear();
        subscriptionQueues.values().forEach(q -> {
            try { q.close(); } catch (Exception ignored) {}
        });
        subscriptionQueues.clear();
        try {
            if (qmgr != null && qmgr.isConnected()) {
                qmgr.disconnect();
            }
        } catch (Exception ignored) {}
        qmgr = null;
    }

    @Override
    public boolean isConnected() {
        return qmgr != null && qmgr.isConnected();
    }

    @Override
    public MQConnectionConfig getConfig() { return config; }

    @Override
    public CompletableFuture<List<MQDestination>> listDestinations() {
        return CompletableFuture.completedFuture(List.of());
    }

    // ── 浏览消息 ──

    @Override
    public CompletableFuture<List<MQMessage>> browseMessages(String destination, int maxCount) {
        return CompletableFuture.supplyAsync(() -> {
            List<MQMessage> result = new ArrayList<>();
            com.ibm.mq.MQQueue queue = null;
            try {
                int openOptions = MQConstants.MQOO_BROWSE;
                queue = qmgr.accessQueue(destination, openOptions);

                com.ibm.mq.MQGetMessageOptions gmo = new com.ibm.mq.MQGetMessageOptions();
                gmo.options = MQConstants.MQGMO_BROWSE_NEXT |
                              MQConstants.MQGMO_NO_WAIT |
                              MQConstants.MQGMO_ACCEPT_TRUNCATED_MSG;

                for (int i = 0; i < maxCount; i++) {
                    com.ibm.mq.MQMessage mqMsg = new com.ibm.mq.MQMessage();
                    try {
                        queue.get(mqMsg, gmo);
                    } catch (MQException e) {
                        if (e.completionCode == MQConstants.MQCC_FAILED
                                && e.reasonCode == MQConstants.MQRC_NO_MSG_AVAILABLE) {
                            break;
                        }
                        throw e;
                    }
                    result.add(toCoreMessage(mqMsg, destination));
                }
            } catch (Exception e) {
                throw new RuntimeException("Browse failed: " + e.getMessage(), e);
            } finally {
                if (queue != null) {
                    try { queue.close(); } catch (Exception ignored) {}
                }
            }
            return result;
        }, executor);
    }

    // ── 发送消息 ──

    @Override
    public CompletableFuture<Void> sendMessage(String destination, MQMessage message) {
        return CompletableFuture.runAsync(() -> {
            com.ibm.mq.MQQueue queue = null;
            try {
                queue = qmgr.accessQueue(destination, MQConstants.MQOO_OUTPUT);

                com.ibm.mq.MQMessage mqMsg = new com.ibm.mq.MQMessage();
                mqMsg.format = MQConstants.MQFMT_STRING;
                mqMsg.characterSet = 1208;
                mqMsg.messageId = parseHex(message.getMessageId(), 24);
                mqMsg.correlationId = parseHex(message.getCorrelationId(), 24);
                if (message.getReplyTo() != null) {
                    mqMsg.replyToQueueName = message.getReplyTo();
                }
                if (message.getContentType() != null) {
                    mqMsg.format = message.getContentType();
                }

                byte[] payload = message.getPayload();
                if (payload == null) {
                    payload = message.getPayloadText().getBytes(StandardCharsets.UTF_8);
                }
                mqMsg.write(payload);

                com.ibm.mq.MQPutMessageOptions pmo = new com.ibm.mq.MQPutMessageOptions();
                queue.put(mqMsg, pmo);
            } catch (Exception e) {
                throw new RuntimeException("Send failed: " + e.getMessage(), e);
            } finally {
                if (queue != null) {
                    try { queue.close(); } catch (Exception ignored) {}
                }
            }
        }, executor);
    }

    // ── 消费消息 ──

    @Override
    public CompletableFuture<List<MQMessage>> consumeMessages(String destination, int maxCount, long timeoutMs) {
        return CompletableFuture.supplyAsync(() -> {
            List<MQMessage> result = new ArrayList<>();
            com.ibm.mq.MQQueue queue = null;
            try {
                queue = qmgr.accessQueue(destination, MQConstants.MQOO_INPUT_AS_Q_DEF);

                com.ibm.mq.MQGetMessageOptions gmo = new com.ibm.mq.MQGetMessageOptions();
                gmo.options = MQConstants.MQGMO_NO_WAIT |
                              MQConstants.MQGMO_ACCEPT_TRUNCATED_MSG;

                long deadline = System.currentTimeMillis() + timeoutMs;
                for (int i = 0; i < maxCount; i++) {
                    if (System.currentTimeMillis() > deadline) break;

                    com.ibm.mq.MQMessage mqMsg = new com.ibm.mq.MQMessage();
                    try {
                        queue.get(mqMsg, gmo);
                    } catch (MQException e) {
                        if (e.completionCode == MQConstants.MQCC_FAILED
                                && e.reasonCode == MQConstants.MQRC_NO_MSG_AVAILABLE) {
                            break;
                        }
                        throw e;
                    }
                    result.add(toCoreMessage(mqMsg, destination));
                }
            } catch (Exception e) {
                throw new RuntimeException("Consume failed: " + e.getMessage(), e);
            } finally {
                if (queue != null) {
                    try { queue.close(); } catch (Exception ignored) {}
                }
            }
            return result;
        }, executor);
    }

    // ── 订阅 ──

    @Override
    public Subscription subscribe(String destination, Consumer<MQMessage> handler) {
        String subId = UUID.randomUUID().toString();
        Subscription sub = new Subscription(subId, destination);

        executor.submit(() -> {
            com.ibm.mq.MQQueue queue = null;
            try {
                queue = qmgr.accessQueue(destination, MQConstants.MQOO_INPUT_AS_Q_DEF);
                subscriptionQueues.put(subId, queue);

                com.ibm.mq.MQGetMessageOptions gmo = new com.ibm.mq.MQGetMessageOptions();
                gmo.options = MQConstants.MQGMO_WAIT |
                              MQConstants.MQGMO_ACCEPT_TRUNCATED_MSG;
                gmo.waitInterval = 5000;

                while (sub.isActive() && qmgr != null && qmgr.isConnected()) {
                    com.ibm.mq.MQMessage mqMsg = new com.ibm.mq.MQMessage();
                    try {
                        queue.get(mqMsg, gmo);
                        MQMessage coreMsg = toCoreMessage(mqMsg, destination);
                        handler.accept(coreMsg);
                    } catch (MQException e) {
                        if (e.reasonCode == MQConstants.MQRC_NO_MSG_AVAILABLE) continue;
                        if (!sub.isActive()) break;
                        throw e;
                    }
                }
            } catch (Exception e) {
                System.err.println("[IBM MQ] Subscribe error: " + e.getMessage());
            }
        });

        subscriptions.put(subId, sub);
        return sub;
    }

    @Override
    public void unsubscribe(String subscriptionId) {
        Subscription sub = subscriptions.remove(subscriptionId);
        com.ibm.mq.MQQueue queue = subscriptionQueues.remove(subscriptionId);
        if (sub != null) sub.cancel();
        if (queue != null) {
            try { queue.close(); } catch (Exception ignored) {}
        }
    }

    // ── MQMessage (IBM) → Core MQMessage 转换 ──

    private MQMessage toCoreMessage(com.ibm.mq.MQMessage mqMsg, String destination) throws IOException {
        MQMessage msg = new MQMessage();
        msg.setMessageId(bytesToHex(mqMsg.messageId));
        msg.setCorrelationId(bytesToHex(mqMsg.correlationId));
        msg.setReplyTo(mqMsg.replyToQueueName);
        msg.setTimestamp(System.currentTimeMillis());

        String format = mqMsg.format;
        msg.setContentType(format);

        // 读取消息体
        int dataLength = mqMsg.getDataLength();
        if (dataLength > 0) {
            byte[] body = new byte[dataLength];
            mqMsg.readFully(body);
            msg.setPayload(body);
        } else {
            msg.setPayload(new byte[0]);
        }

        // MQMD 字段 → nativeFields
        msg.setNativeField("MQMD.MsgId", bytesToHex(mqMsg.messageId));
        msg.setNativeField("MQMD.CorrelId", bytesToHex(mqMsg.correlationId));
        msg.setNativeField("MQMD.Format", mqMsg.format);
        msg.setNativeField("MQMD.Expiry", mqMsg.expiry);
        msg.setNativeField("MQMD.Priority", mqMsg.priority);
        msg.setNativeField("MQMD.Persistence", mqMsg.persistence);
        msg.setNativeField("MQMD.ReplyToQ", mqMsg.replyToQueueName);
        msg.setNativeField("MQMD.ReplyToQMgr", mqMsg.replyToQueueManagerName);
        msg.setNativeField("MQMD.PutApplicationName", mqMsg.putApplicationName);
        msg.setNativeField("MQMD.UserId", mqMsg.userId);
        msg.setNativeField("MQMD.ApplIdentityData", mqMsg.applicationIdData);
        msg.setNativeField("MQMD.ApplOriginData", mqMsg.applicationOriginData);
        msg.setNativeField("MQMD.BackoutCount", mqMsg.backoutCount);
        msg.setNativeField("MQMD.MsgType", mqMsg.messageType);
        msg.setNativeField("MQMD.Feedback", mqMsg.feedback);
        msg.setNativeField("MQMD.Encoding", mqMsg.encoding);
        msg.setNativeField("MQMD.CodedCharSetId", mqMsg.characterSet);
        msg.setNativeField("MQMD.GroupId", bytesToHex(mqMsg.groupId));
        msg.setNativeField("MQMD.MsgSeqNumber", mqMsg.messageSequenceNumber);
        msg.setNativeField("MQMD.Offset", mqMsg.offset);
        msg.setNativeField("MQMD.OriginalLength", mqMsg.originalLength);
        msg.setNativeField("MQMD.Report", mqMsg.report);
        msg.setNativeField("MQMD.MsgFlags", mqMsg.messageFlags);
        msg.setNativeField("MQMD.AccountingToken", bytesToHex(mqMsg.accountingToken));

        // putDateTime (java.util.Date) — 新版本 API 替代 putApplicationDate/Time
        if (mqMsg.putDateTime != null) {
            msg.setNativeField("MQMD.PutDateTime",
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
                            .format(mqMsg.putDateTime));
        }

        msg.setNativeField("destination", destination);
        return msg;
    }

    // ── 工具方法 ──

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            hex[i * 2]     = HEX[b >>> 4];
            hex[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(hex);
    }

    private static byte[] parseHex(String hex, int len) {
        if (hex == null || hex.isEmpty()) return new byte[len];
        String clean = hex.replaceAll("[^0-9A-Fa-f]", "");
        int actualLen = Math.min(clean.length() / 2, len);
        byte[] result = new byte[actualLen];
        for (int i = 0; i < actualLen; i++) {
            result[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        if (actualLen < len) {
            byte[] padded = new byte[len];
            System.arraycopy(result, 0, padded, len - actualLen, actualLen);
            return padded;
        }
        return result;
    }
}
