package com.mqclient.olap.base.error

import com.mqclient.olap.base.OlapCategory.I18N_KEY_PREFIX

/**
 * OLAP 协议族的错误分类（对齐 mq-base 的错误翻译机制：枚举携带 i18n 键 + 英文兜底
 * 文案，展示端 `Throwable.userMessage()` 在渲染时点按当前语言表翻译）。
 *
 * 分类是**审计与文案的单一来源**：协议模块把原生错误映射到这里
 * （`SqlDialect.classify` / 连接管理器 `classifyError`），不各自维护错误码表。
 *
 * @param errorKey i18n 翻译键（= [I18N_KEY_PREFIX] + 子路径；资源条目见
 *   ui-common 的 `strings_zh-CN.json` / `strings_en-US.json`，键 `olap.err.*`）。
 * @param fallbackMessage 英文兜底文案（缺键回落路径可见；service 层不产出中文，
 *   中文全部在语言表里）。
 */
enum class OlapErrorCode(
    val errorKey: String,
    val fallbackMessage: String,
) {

    /** 连接 / 建会话失败（网络不可达、认证失败、引擎不响应）。 */
    CONNECTION_FAILED(
        "$I18N_KEY_PREFIX.err.connection_failed",
        "Connection to the analytics engine failed",
    ),

    /** 已连接但无权限（库 / 表级 ACL、只读账号触发了写路径）。 */
    PERMISSION_DENIED(
        "$I18N_KEY_PREFIX.err.permission_denied",
        "Permission denied by the analytics engine",
    ),

    /** 查询超时（会话级或语句级超时参数到点）。 */
    TIMEOUT(
        "$I18N_KEY_PREFIX.err.timeout",
        "Query timed out",
    ),

    /** SQL 语法 / 语义错误（引擎解析器拒绝）。 */
    SYNTAX_ERROR(
        "$I18N_KEY_PREFIX.err.syntax_error",
        "SQL syntax or semantic error",
    ),

    /** 资源超限（内存 / 磁盘 / 并发数 / 结果集上限被引擎拒绝）。 */
    RESOURCE_LIMIT(
        "$I18N_KEY_PREFIX.err.resource_limit",
        "Query exceeded engine resource limits",
    ),

    /** 用户取消（幂等终态；不是失败——审计按 CANCELLED 口径记录）。 */
    CANCELLED(
        "$I18N_KEY_PREFIX.err.cancelled",
        "Query was cancelled",
    ),

    /** 未建立会话就尝试查询（withSession 在无会话时抛出）。 */
    NOT_CONNECTED(
        "$I18N_KEY_PREFIX.err.not_connected",
        "No active session; connect first",
    ),

    /** 无法归类的兜底（协议模块未覆盖的错误形态）。 */
    UNKNOWN(
        "$I18N_KEY_PREFIX.err.unknown",
        "Unknown analytics engine error",
    ),
}
