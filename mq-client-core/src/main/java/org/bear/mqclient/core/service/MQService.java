package org.bear.mqclient.core.service;

import org.bear.mqclient.core.model.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * MQ 服务接口 — 所有 MQ 客户端需实现此接口。
 * <p>
 * 方法均为异步（CompletableFuture），各实现自行调度到后台线程。
 */
public interface MQService {

    /** 建立连接 */
    CompletableFuture<Void> connect(MQConnectionConfig config);

    /** 断开连接 */
    void disconnect();

    /** 是否已连接 */
    boolean isConnected();

    /** 当前连接配置 */
    MQConnectionConfig getConfig();

    /** 列出目的地（队列/主题） */
    CompletableFuture<List<MQDestination>> listDestinations();

    /** 浏览消息（非破坏性读取） */
    CompletableFuture<List<MQMessage>> browseMessages(String destination, int maxCount);

    /** 发送消息 */
    CompletableFuture<Void> sendMessage(String destination, MQMessage message);

    /** 消费消息（破坏性读取，超时后返回空列表） */
    CompletableFuture<List<MQMessage>> consumeMessages(String destination, int maxCount, long timeoutMs);

    /** 订阅消息（持续监听） */
    Subscription subscribe(String destination, java.util.function.Consumer<MQMessage> handler);

    /** 取消订阅 */
    void unsubscribe(String subscriptionId);
}
