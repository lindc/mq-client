package com.mqclient.starrocks.catalog

/**
 * 数据目录的元数据 SQL 构造（表清单 / 字段 / 分区 / DDL / 预览）。
 *
 * 为什么在 service 而不是 UI：模块里 cluster/ 与 system/ 两个包都把「发什么 SQL」
 * 放在 service，UI 只负责渲染。数据目录此前把这一层写进了面板文件，导致同一个
 * 模块里一半 SQL 在 service、一半在 UI —— 新加面板时得先猜惯例是哪一种，UI 层的
 * SQL 也无法被 service 的测试覆盖。
 *
 * 标识符与字面量的转义就在这里收口：表名来自 information_schema（相对可信），
 * 但仍可能带引号 / 反引号，调用方不得自行拼 SQL。
 */

const val TABLES_SQL =
    "SELECT table_name, table_comment, table_type FROM information_schema.tables " +
        "WHERE table_schema = DATABASE();"

/**
 * 物化视图名单：`SHOW MATERIALIZED VIEWS`（StarRocks 专属，3.4.0 实测可靠）。
 * 不能靠 information_schema.tables.table_type == 'MATERIALIZED VIEW' ——
 * 3.4 的异步物化视图实际返回 `VIEW`（已实测），会被误判为普通视图。
 * MySQL 不支持该语句，调用方静默降级为空集。
 */
const val MATERIALIZED_VIEW_SQL = "SHOW MATERIALIZED VIEWS;"

/** 表名进 SQL 字符串字面量：单引号转义，防元数据里带引号的名称破坏语句。 */
fun columnsSql(table: String): String =
    "SELECT column_name, data_type, is_nullable, column_key, column_default, extra, column_comment " +
        "FROM information_schema.columns " +
        "WHERE table_schema = DATABASE() AND table_name = '${table.replace("'", "''")}';"

/** 分区查询：StarRocks 语法；MySQL 会报错，由调用方静默降级为「不支持」。 */
fun partitionsSql(table: String): String =
    "SHOW PARTITIONS FROM `${table.replace("`", "``")}`;"

/** 双击表名生成的查询 SQL：标识符用反引号包裹（内部反引号双写）。 */
fun previewSql(table: String): String =
    "SELECT * FROM `${table.replace("`", "``")}` LIMIT 100;"

/** DDL 查询：标识符用反引号包裹（内部反引号双写）。 */
fun ddlSql(table: String): String =
    "SHOW CREATE TABLE `${table.replace("`", "``")}`;"
