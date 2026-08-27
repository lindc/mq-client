package io.mqclient.core.model;

import java.util.Objects;

/**
 * MQ 目的地（队列 / 主题 / Exchange 等）的统一模型。
 */
public class MQDestination {

    public enum Type {
        QUEUE,
        TOPIC,
        EXCHANGE,
        VIRTUAL_TOPIC,
        OTHER
    }

    private final String name;
    private final Type type;
    private int messageCount;

    public MQDestination(String name, Type type) {
        this.name = Objects.requireNonNull(name);
        this.type = Objects.requireNonNull(type);
    }

    public String getName() { return name; }
    public Type getType() { return type; }
    public int getMessageCount() { return messageCount; }
    public void setMessageCount(int count) { this.messageCount = count; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MQDestination that)) return false;
        return name.equals(that.name) && type == that.type;
    }

    @Override
    public int hashCode() { return Objects.hash(name, type); }

    @Override
    public String toString() {
        return type + ":" + name;
    }
}
