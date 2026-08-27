package org.bear.mqclient.core.model;

/**
 * 订阅句柄，用于取消订阅或查询订阅状态。
 */
public class Subscription {

    private final String id;
    private final String destination;
    private volatile boolean active;

    public Subscription(String id, String destination) {
        this.id = id;
        this.destination = destination;
        this.active = true;
    }

    public String getId() { return id; }
    public String getDestination() { return destination; }
    public boolean isActive() { return active; }
    public void cancel() { this.active = false; }
}
