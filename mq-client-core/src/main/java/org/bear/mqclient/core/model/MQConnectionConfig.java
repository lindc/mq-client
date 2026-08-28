package org.bear.mqclient.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * MQ 连接配置。
 * 通用字段由 core 管理，MQ 特有字段存于 {@code properties} map 中。
 */
public class MQConnectionConfig {

    private final String id;
    private String name;
    private String type;          // "IBM_MQ", "KAFKA", "ARTEMIS", "RABBITMQ", "ROCKETMQ"
    private final Map<String, String> properties;

    @JsonCreator
    public MQConnectionConfig(@JsonProperty("id") String id) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.properties = new LinkedHashMap<>();
    }

    // ── Getters / Setters ──

    public String getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Map<String, String> getProperties() { return properties; }

    public String getProperty(String key) { return properties.get(key); }
    public String getProperty(String key, String defaultValue) {
        String v = properties.get(key);
        return v != null ? v : defaultValue;
    }
    public void setProperty(String key, String value) { properties.put(key, value); }
    public void setProperties(Map<String, String> props) {
        properties.clear();
        if (props != null) properties.putAll(props);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MQConnectionConfig that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }

    @Override
    public String toString() {
        return name != null ? name : type + ":" + id.substring(0, 8);
    }
}
