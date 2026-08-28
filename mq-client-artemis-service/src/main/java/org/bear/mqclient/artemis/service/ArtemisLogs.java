package org.bear.mqclient.artemis.service;

import org.bear.mqclient.core.util.LogAdapter;

/**
 * Artemis service 模块统一日志器。
 * <p>
 * source 固定为 "ARTEMIS"，包内所有类复用此常量，避免各处手写 source 字符串。
 */
public final class ArtemisLogs {

    public static final LogAdapter.Log LOG = LogAdapter.forSource("ARTEMIS");

    private ArtemisLogs() {
    }
}
