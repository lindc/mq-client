package org.bear.mqclient.ibm.service;

import org.bear.mqclient.core.util.LogAdapter;

/**
 * IBM MQ service 模块统一日志器。
 * <p>
 * source 固定为 "IBM_MQ"，包内所有类（Service、Provider、未来的工具类等）
 * 复用此常量，避免各处手写 source 字符串导致打错或不一致。
 */
public final class IBMMQLogs {

    public static final LogAdapter.Log LOG = LogAdapter.forSource("IBM_MQ");

    private IBMMQLogs() {
    }
}
