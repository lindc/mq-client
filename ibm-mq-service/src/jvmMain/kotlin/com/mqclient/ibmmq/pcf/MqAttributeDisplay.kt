package com.mqclient.ibmmq.pcf

/**
 * PCF 属性的「显示层」解析：把快照里的原始属性名 / 整型枚举值翻译成 MQ Explorer
 * 同款的可读名字。名字 / 值的文案本身不在本类 —— 都走 i18n 键
 * `MQXXX.title`（模块表 `ibm_mq_attrs_*`，由 `scripts/gen_mq_attr_display.py` 从
 * 本机 MQ Explorer 的 common.properties 提取，en / zh-CN 双语），UI 侧直接
 * `stringResource("$rawName.title")` 即可；本类只负责**枚举值 → 常量名**的归属判定。
 *
 * 同一个整数值在 MQConstants 里往往命中一族常量（1 = MQQT_LOCAL = MQPER_PERSISTENT
 * = MQQA_GET_ALLOWED = …），只有「该属性自己的合法枚举集」内的常量才显示正确。
 * 归属集来自 MQ Explorer 的数据模型（DmQueue.xml，[MqQueueAttrEnums]），与
 * [MqAttributeNameMapper.namesForValue] 的反射全表求交即可去歧义。
 */
object MqAttributeDisplay {

    /**
     * 属性 [attrName] 的整型值 [value] → 应显示的 MQ 常量名（如 `MQQT_LOCAL`）。
     * 属性不是枚举 / 值不在其枚举集内 → null，调用方回落显示原始值。
     */
    fun constNameFor(attrName: String, value: Int): String? {
        val members = attrEnums[attrName] ?: return null
        return MqAttributeNameMapper.namesForValue(value).firstOrNull { it in members }
    }

    /**
     * DmQueue / DmQueueManager 两表合并（同名属性成员列表拼接）：个别属性在两个
     * 模型里的枚举集不同宽（如 MQIA_STATISTICS_Q 队列侧无 MQMON_NONE、队列管理器
     * 侧有），并集才能让两侧取值都命中唯一常量。
     */
    private val attrEnums: Map<String, List<String>> by lazy {
        val merged = HashMap<String, List<String>>()
        for (table in listOf(MqQueueAttrEnums.byAttr, MqQMgrAttrEnums.byAttr)) {
            for ((attr, members) in table) merged.merge(attr, members) { a, b -> a + b }
        }
        merged
    }
}
