package com.mqclient.olap.base.dialect

import com.mqclient.olap.base.error.OlapErrorCode

/**
 * SQL 方言抽象：OLAP 协议族在 SQL 语法上的差异点全部收敛到这里。
 *
 * 这是 olap-base 里**新增、无 mq-base 对应物**的概念（MQ 协议没有 SQL）——必须
 * 放在 olap-base 而非下游模块的原因：目录扫描的元数据查询、查询执行器的只读
 * 包装、EXPLAIN 前缀都要经过它，三个模板共用一个方言入口才能保证「同一连接的
 * 语法处理一致」；放下游会导致每个协议模块自己拼 SQL，退回到三份副本漂移的老路。
 *
 * 实现要求：
 *  - 全部方法**无副作用**（纯函数或只读判定），可安全并发调用；
 *  - 不做 SQL 注入防护的兜底承诺 —— [stringLiteral] 只做字面量转义，参数绑定
 *    责任在执行器（只读查询禁止参数内联）；
 *  - [classify] 的文本匹配是**兜底**而非主判定：协议模块应优先按原生错误码
 *    结构化分类（RabbitMQ 那轮的教训：broker 消息文本随版本变化）。
 */
interface SqlDialect {

    /** 标识符转义（表名 / 列名进 SQL 前调用，防保留字与特殊字符）。 */
    fun quoteIdentifier(identifier: String): String

    /** 字符串字面量转义（值进 SQL 前调用）。 */
    fun stringLiteral(value: String): String

    /** 生成 `LIMIT/OFFSET` 子句（方言差异：Doris 无 OFFSET 兼容旧语法等）。 */
    fun limitOffset(limit: Int, offset: Int = 0): String

    /** 给查询加 EXPLAIN 前缀（已含 EXPLAIN 的查询原样返回，不重复包裹）。 */
    fun explainPrefix(sql: String): String

    /**
     * keyset 分页谓词（优于 OFFSET 深翻页）。
     *
     * @param column 游标列（已转义或由实现转义）。
     * @param lastValue 上一页末行的游标值；null = 首页（返回 null，调用方只拼 LIMIT）。
     * @return WHERE 谓词片段（如 `"ts" > '2026-01-01 00:00:00'`），调用方拼进 WHERE。
     */
    fun keysetPredicate(column: String, lastValue: String?): String?

    /**
     * 只读会话开启语句（如 `SET readonly = 1`）；协议不需要显式开启时返回 null。
     */
    fun readOnlySessionStatement(): String?

    /**
     * 原生错误 → [OlapErrorCode] 分类（审计与用户文案用）。文本匹配是兜底，
     * 协议实现应覆盖为错误码结构化判断。
     */
    fun classify(error: Throwable): OlapErrorCode
}
