package com.mqclient.ibmmq.ui.properties

import com.mqclient.ibmmq.pcf.MqAttributeDisplay
import com.mqclient.ibmmq.pcf.MqAttributeNameMapper
import com.mqclient.ibmmq.pcf.MqObjectSnapshot

/**
 * 通用属性解析（queue 以外的对象对话框共用）：字段表命中 + 「全部属性」兜底。
 *
 * 枚举归属一律不用 DM 表（按 Queue 模型生成，对其他对象键会误判）：
 * constNameOf 可覆盖（queue 用 DM 优先链），默认 valueConstMap → valuePrefix 反查。
 */
internal fun resolveGroupedObjectProperties(
    snapshot: MqObjectSnapshot,
    groups: List<ObjectPropertyGroup>,
    allGroupTitleKey: String,
    hiddenLeftoverKeys: Set<String> = emptySet(),
    fieldFilter: (ObjectPropertyField) -> Boolean = { true },
    constNameOf: ((ObjectPropertyField, String, Int) -> String?)? = null,
): List<Pair<ObjectPropertyGroup, List<ResolvedObjectProperty>>> {
    val attrs = snapshot.attributes
    val usedKeys = mutableSetOf<String>()

    fun resolve(field: ObjectPropertyField): ResolvedObjectProperty? {
        if (!fieldFilter(field)) return null
        val key = field.keys.firstOrNull { it in attrs }
        if (key == null) {
            if (!field.showIfMissing) return null
            return ResolvedObjectProperty(
                nameKey = field.nameKey,
                descKey = field.descKey,
                labelEn = field.labelEn,
                valueKey = "ibm_mq_queue_value_none",
                valueFallbackKey = null,
                rawValue = " ",
                rawConstName = null,
            )
        }
        usedKeys += key
        val raw = attrs[key]
        val rawText = raw?.toString()?.trim() ?: ""
        val constName = if (raw is Int) {
            constNameOf?.invoke(field, key, raw)
                ?: field.valueConstMap[raw]
                ?: field.valuePrefix?.let { MqAttributeNameMapper.lookupName(raw, it) }
        } else {
            null
        }
        return ResolvedObjectProperty(
            nameKey = field.nameKey,
            descKey = field.descKey,
            labelEn = field.labelEn,
            valueKey = constName?.let(::curatedValueKey),
            valueFallbackKey = constName?.let { "$it.title" },
            rawValue = rawText.ifBlank { " " },
            rawConstName = constName,
        )
    }

    val grouped = groups.map { group ->
        group to group.fields.mapNotNull(::resolve)
    }.filter { (_, rows) -> rows.isNotEmpty() }

    // 「全部属性」兜底：名字直接查 `MQXXX.title` i18n 键；整型值仍走 DM 枚举反查
    // （与 queue 的兜底分支同款）——快照里未分组的属性（z/OS 专属、遥测能力等）
    // 大多在 DmQueueManager 里有枚举定义，不反查就会漏出原始数字。查不到归属 /
    // 常量名的（真数值字段）回落原始值，不丢数据。
    val other = attrs.filterKeys { it !in usedKeys && it !in hiddenLeftoverKeys }
        .map { (k, v) ->
            val constName = (v as? Int)?.let { MqAttributeDisplay.constNameFor(k, it) }
            ResolvedObjectProperty(
                nameKey = "$k.title",
                descKey = "",
                labelEn = k,
                valueKey = null,
                valueFallbackKey = constName?.let { "$it.title" },
                rawValue = v?.toString()?.trim()?.ifBlank { " " } ?: "",
                rawConstName = constName,
            )
        }
    return if (other.isEmpty()) grouped else grouped + (ObjectPropertyGroup(allGroupTitleKey, emptyList()) to other)
}
