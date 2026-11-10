package com.mqclient.starrocks.jdbc

import com.mqclient.olap.base.dialect.DefaultSqlDialect
import com.mqclient.olap.base.dialect.SqlDialect
import com.mqclient.olap.base.error.OlapErrorCode
import com.mqclient.core.ServiceException

/**
 * StarRocks 的 SQL 方言：ANSI 部分委托 [DefaultSqlDialect]，只覆写错误分类。
 *
 * 错误映射走 olap-base 的机制：先把 [JdbcFailure] 的 SQLState / ErrorCode
 * 分类为 [OlapErrorCode]（带 i18n `errorKey` + 英文兜底 `fallbackMessage`），
 * 再由 [friendlyDetail] 生成 `HealthReport.detail`，抛出异常时用
 * [toServiceException] 构造 `ServiceException`（UI 的 `userMessage()` 在展示
 * 时点按当前语言表翻译）。
 */
object StarRocksSqlDialect : SqlDialect by DefaultSqlDialect {

    /** SQLState / ErrorCode → [OlapErrorCode]（结构化，不做消息文本匹配）。 */
    override fun classify(error: Throwable): OlapErrorCode = when {
        error is JdbcConnectTimeoutException -> OlapErrorCode.TIMEOUT
        error is JdbcFailure -> when {
            // 08xxx = connection exception（08S01 通信链路失败、08006 连接失败…）。
            error.sqlState?.startsWith("08") == true -> OlapErrorCode.CONNECTION_FAILED
            error.sqlState == "28000" || error.errorCode == 1045 -> OlapErrorCode.PERMISSION_DENIED
            error.errorCode == 1064 || error.sqlState == "42000" -> OlapErrorCode.SYNTAX_ERROR
            error.errorCode == 1203 || error.errorCode == 1226 -> OlapErrorCode.RESOURCE_LIMIT
            error.errorCode == 1317 -> OlapErrorCode.CANCELLED
            // 2006/2013 = server gone away / lost connection。
            error.errorCode == 2006 || error.errorCode == 2013 -> OlapErrorCode.CONNECTION_FAILED
            else -> DefaultSqlDialect.classify(error)
        }
        else -> DefaultSqlDialect.classify(error)
    }
}

/**
 * 错误 → 用户可读文案的单一出口（空骨架阶段先落在 `HealthReport.detail`）。
 *
 * 文案本体取自 [OlapErrorCode.fallbackMessage]（英文兜底，中文在 i18n
 * `olap.err.*` 键里，由 UI 展示时按 [OlapErrorCode.errorKey] 翻译），
 * 后面附上 SQLState / ErrorCode 便于运维定位。
 */
object StarRocksErrorDetail {

    /** 生成 `HealthReport.detail`：友好文案 + 错误键 + 协议侧诊断字段。 */
    fun of(code: OlapErrorCode, error: Throwable): String = buildString {
        append(code.fallbackMessage)
        append(" (").append(code.errorKey).append(")")
        when (error) {
            is JdbcFailure -> append(" [SQLState=${error.sqlState ?: "-"}, ErrorCode=${error.errorCode}]")
            is JdbcConnectTimeoutException -> append(" [connect timeout]")
            else -> append(" [type=${error::class.simpleName ?: "unknown"}]")
        }
    }

    /**
     * 封装成 app-core 的 [ServiceException]：UI 侧 `Throwable.userMessage()`
     * 会按 `errorKey` 在展示时点翻译（支持语言热切换）。
     */
    fun toServiceException(error: Throwable): ServiceException {
        val code = StarRocksSqlDialect.classify(error)
        return ServiceException(
            errorKey = code.errorKey,
            fallbackMessage = code.fallbackMessage,
            cause = error,
        )
    }
}
