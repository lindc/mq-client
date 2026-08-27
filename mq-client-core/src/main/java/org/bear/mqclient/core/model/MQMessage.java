package org.bear.mqclient.core.model;

import java.util.*;

/**
 * 统一消息模型。
 * <p>
 * 通用字段由 core 定义；MQ 特有原字段由各 MQ service 存入 {@code nativeFields}，
 * 各 MQ UI 的 MessageDetailViewFactory 从中读取自己的特有信息展示。
 */
public class MQMessage {

    private String messageId;
    private byte[] payload;
    private String contentType;       // "text/plain", "application/json", "application/octet-stream" ...
    private final Map<String, String> headers;
    private final Map<String, Object> userProperties;
    private long timestamp;
    private String correlationId;
    private String replyTo;
    private final Map<String, Object> nativeFields;

    public MQMessage() {
        this.headers = new LinkedHashMap<>();
        this.userProperties = new LinkedHashMap<>();
        this.nativeFields = new LinkedHashMap<>();
        this.timestamp = System.currentTimeMillis();
    }

    // ── Getters / Setters ──

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public byte[] getPayload() { return payload; }
    public void setPayload(byte[] payload) { this.payload = payload; }

    public String getPayloadText() {
        if (payload == null) return "";
        // 尝试 UTF-8 解码；如果内容不是合法 UTF-8 则返回 hex
        try {
            return new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return toHex(payload);
        }
    }

    public void setPayloadText(String text) {
        this.payload = text != null
                ? text.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                : new byte[0];
    }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeader(String key, String value) { headers.put(key, value); }

    public Map<String, Object> getUserProperties() { return userProperties; }
    public void setUserProperty(String key, Object value) { userProperties.put(key, value); }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public String getReplyTo() { return replyTo; }
    public void setReplyTo(String replyTo) { this.replyTo = replyTo; }

    /** MQ 特有原字段（如 IBM MQ 的 MQMD、RFH2；Kafka 的 offset/partition；RabbitMQ 的 exchange/routingKey 等） */
    public Map<String, Object> getNativeFields() { return nativeFields; }

    @SuppressWarnings("unchecked")
    public <T> T getNativeField(String key) { return (T) nativeFields.get(key); }
    public void setNativeField(String key, Object value) { nativeFields.put(key, value); }

    // ── 工具 ──

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static String toHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            hex[i * 2]     = HEX[b >>> 4];
            hex[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(hex);
    }
}
