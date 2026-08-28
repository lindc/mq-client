package org.bear.mqclient.core.model;

/**
 * 连接状态 — 驱动左侧连接列表卡片上的状态小圆点颜色。
 * <p>
 * 由 MainController 在连接流程中更新（连接中 → 已连接/失败，断开 → 未连接）。
 */
public enum ConnectionState {
    /** 未连接（默认） */
    DISCONNECTED,
    /** 连接中 */
    CONNECTING,
    /** 已连接 */
    CONNECTED,
    /** 连接失败 */
    FAILED
}
