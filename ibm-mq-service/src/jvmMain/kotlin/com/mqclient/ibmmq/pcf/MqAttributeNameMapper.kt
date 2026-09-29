package com.mqclient.ibmmq.pcf

import com.ibm.mq.constants.MQConstants
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * PCF 参数 ID / 常量值 → 可读名字的映射（如 2016 → `MQCA_Q_NAME`）。
 *
 * 客户端**没有**现成的 id→name 直查方法（`MQConstants.lookup(value, prefix)` 实测
 * 对参数 ID 返回 null —— 单测抓出来的）。这里用反射把 [MQConstants] 及其实现的
 * CMQ* 接口上全部 `static final int` 常量枚举一遍，构建 value → 名字列表的表，
 * 进程内只构建一次：
 *  - [getName]：参数 ID 反查，名字限定 PCF 参数五族前缀；
 *  - [lookupName]：常量值反查，限定调用方给的前缀（如 MQCHT / MQCHS）。
 * 全不中回落 `ATTR_<id>` / null，绝不让名字映射失败拖垮扫描。
 */
object MqAttributeNameMapper {

    /**
     * PCF 参数 ID 的六族名字前缀（注意 MQCA_ 不带尾下划线会误吞 MQIACF_，全带）。
     * MQIACH_（通道整型选择器，如 MQIACH_CHANNEL_TYPE=1511）曾是漏网之鱼 —— 缺了它
     * 所有通道整型属性都落成 `ATTR_<id>`，拓扑边提取等按可读键取值的逻辑全部落空。
     */
    private val ID_PREFIXES = listOf("MQCACH_", "MQCACF_", "MQIACF_", "MQIACH_", "MQCA_", "MQIA_")

    private val cache = ConcurrentHashMap<Int, String>()

    /** value → 同值常量名列表（不同前缀族的常量可能同值，全收，查询时按前缀筛）。 */
    private val constantsByValue: Map<Int, List<String>> by lazy {
        val classes = buildList {
            add(MQConstants::class.java)
            addAll(MQConstants::class.java.interfaces)
        }
        val result = LinkedHashMap<Int, MutableList<String>>()
        for (clazz in classes) {
            for (field in clazz.declaredFields) {
                if (field.type != Int::class.java) continue
                if (!Modifier.isStatic(field.modifiers)) continue
                val value = runCatching { field.getInt(null) }.getOrNull() ?: continue
                result.getOrPut(value) { mutableListOf() }.add(field.name)
            }
        }
        result
    }

    /** PCF 参数 ID → 可读属性名（如 2016 → `MQCA_Q_NAME`）；未知 ID → `ATTR_<id>`。 */
    fun getName(paramId: Int): String = cache.computeIfAbsent(paramId) { id ->
        constantsByValue[id]
            ?.firstOrNull { name -> ID_PREFIXES.any { name.startsWith(it) } }
            ?: "ATTR_$id"
    }

    /**
     * 常量值 → 常量名（限 [prefix] 前缀族，如 MQCHS_RUNNING 的值查 "MQCHS"；
     * 也支持**精确常量名**——单常量枚举（如 topic 通配符操作只有
     * MQTA_PASSTHRU=2 / MQTA_BLOCK=1，没有子族后缀可依）直接传常量名）。
     * 查不到（客户端未收录，如 MQLSR_*）返回 null，由调用方给兜底文案。
     */
    fun lookupName(value: Int, prefix: String): String? =
        constantsByValue[value]?.firstOrNull { it == prefix || it.startsWith("$prefix" + "_") }

    /** 常量值 → 全部同值常量名（跨前缀族）；调用方按属性自己的枚举归属筛选。 */
    fun namesForValue(value: Int): List<String> = constantsByValue[value] ?: emptyList()
}
