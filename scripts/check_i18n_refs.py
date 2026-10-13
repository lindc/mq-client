#!/usr/bin/env python3
"""键引用覆盖检查（i18n 键命名空间的安全网）。

编译和单测抓不到下面这些 —— 它们都不报错，只是界面上直接显示 key 字符串或原始数字：

  1. **静态引用漏改** —— key 改了名但调用点没跟着改；
  2. **运行时拼接的键查不到**，共三条拼法：
     - `valueConstMap` 常量名 → 策展键 `queue_value_*` → 兜底键 `<CONST>.title`
     - DM 枚举归属 `MqAttributeDisplay.constNameFor` → `<CONST>.title`
       （队列 / 队列管理器先走这条，命中即遮蔽 `valuePrefix`）
     - `valuePrefix` → `MqAttributeNameMapper.lookupName` 反射 jar → `<CONST>.title`

表：ibm_mq 模块表 + ibm_mq_attrs 属性名表 + ui-common 公共表。
后端常量名的解析链必须与 ObjectPropertiesResolver / QueuePropertyGroups 保持一致
（见 CURATED_PREFIXES 与 SHADOWING_FILES）。
"""
import json
import os
import re
import sys

LANG = sys.argv[1] if len(sys.argv) > 1 else "zh-CN"
MODULE_TABLE = "ibm-mq-ui/src/commonMain/composeResources/files/i18n/ibm_mq_%s.json" % LANG
COMMON_TABLE = "ui-common/src/commonMain/composeResources/files/i18n/strings_%s.json" % LANG
# 属性名表：`MQXXX.title` 全在这儿（脚本从 MQ Explorer 生成，手工写的键别放进去）
ATTRS_TABLE = "ibm-mq-ui/src/commonMain/composeResources/files/i18n/ibm_mq_attrs_%s.json" % LANG
# 两个源码根都要扫：导航项的 titleKey 住在 ibm-mq-service，而不是 -ui。
KT_ROOTS = ["ibm-mq-ui/src", "ibm-mq-service/src"]

# 静态引用点：直接调用 + 属性组数据里的键字段 + 卡片操作 tooltip 等
# 注意别收 `label = "..."`：Compose 的 rememberInfiniteTransition(label=) /
# animateFloat(label=) 也长这样，会误抓（edgeFlow / flowGap）。
PATTERNS = [
    re.compile(r'stringResource\("([^"$]+)"\)'),
    re.compile(r'\b(nameKey|descKey|valueKey|valueFallbackKey|titleKey|tooltip)\s*=\s*"([^"$]+)"'),
]
# 属性名表（ibm_mq_attrs_*.json）的键：`MQXXX.title`，常量名自带命名空间，豁免
ATTR_KEY = re.compile(r"^(MQ[A-Z0-9_]+)\.title$")

module = set(json.load(open(MODULE_TABLE, encoding="utf-8")))
common = set(json.load(open(COMMON_TABLE, encoding="utf-8")))
attrs = set(json.load(open(ATTRS_TABLE, encoding="utf-8")))

# 命名空间规则：模块表里**不得**出现「加了前缀的常量名键」（如 ibm_mq_MQXXX.title）。
# 这类键按常量名在运行时拼出来（valueConstMap → `<CONST>.title`），加前缀就再也查不到 ——
# 回归过一次：MQEXPIRY_NOLIMIT.title 被改成 ibm_mq_MQEXPIRY_NOLIMIT.title，界面上直接显示 -1。
misprefixed = sorted(k for k in module if re.match(r"^ibm_mq_[A-Z][A-Z0-9_]*\.title$", k))
if misprefixed:
    print("!! 模块表里有被误加前缀的常量名键（运行时按常量名拼，加前缀即查不到）：")
    for key in misprefixed:
        print("   ", key)
    sys.exit(1)

referenced = {}
for kt_root in KT_ROOTS:
    for root, _, names in os.walk(kt_root):
        for name in names:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(root, name)
            text = open(path, encoding="utf-8").read()
            for pattern in PATTERNS:
                for match in pattern.finditer(text):
                    key = match.groups()[-1]
                    referenced.setdefault(key, []).append(path)

unresolved = {}
exempt = 0
for key, sites in sorted(referenced.items()):
    if key in module or key in common:
        continue
    if ATTR_KEY.match(key):
        exempt += 1
        continue
    unresolved[key] = sites

print("静态引用键 %d 个（已解析 %d，属性名表豁免 %d）"
      % (len(referenced), len(referenced) - len(unresolved) - exempt, exempt))
if unresolved:
    print("\n!! 解析不到的键 %d 个：" % len(unresolved))
    for key, sites in list(unresolved.items())[:40]:
        print("   %-52s %s" % (key, sites[0].split("/")[-1]))
    sys.exit(1)

# 运行时拼接的键：valueConstMap 的常量名经“策展键 → <CONST>.title”两级解析，
# 两级都查不到就回落原始值（界面上看到 1/2/3/5 这种数字）。
# 策展键的前缀剥离规则必须与 QueuePropertyGroups.curatedValueKey 一致。
CURATED_PREFIXES = ["mq", "qa_", "ia_", "ca_", "ot_", "us_", "qt_", "per_", "sco_",
                    "bnd_", "mds_", "evo_", "tc_", "trg_", "ha_", "npm_", "prop_", "clwluseq_"]
CONST_MAP = re.compile(r"valueConstMap = mapOf\((.*?)\n\s*\)", re.S)
CONST_NAME = re.compile(r'"([A-Z][A-Z0-9_]*)"')


def curated_value_key(const_name):
    text = const_name.lower()
    for prefix in CURATED_PREFIXES:
        if text.startswith(prefix):
            text = text[len(prefix):]
    return "queue_value_" + text


const_names = set()
for kt_root in KT_ROOTS:
    for root, _, names in os.walk(kt_root):
        for name in names:
            if not name.endswith(".kt"):
                continue
            text = open(os.path.join(root, name), encoding="utf-8").read()
            for block in CONST_MAP.findall(text):
                const_names.update(CONST_NAME.findall(block))

known = module | common | attrs
unresolved_consts = [
    const for const in sorted(const_names)
    if curated_value_key(const) not in known and (const + ".title") not in known
]
print("运行时拼接键（valueConstMap 常量名）%d 个，查不到的 %d 个"
      % (len(const_names), len(unresolved_consts)))
if unresolved_consts:
    print("\n!! 策展键与 <CONST>.title 都查不到（属性值会显示原始数字）：")
    for const in unresolved_consts:
        print("   %-28s 策展键 %s / 兜底 %s.title" % (const, curated_value_key(const), const))
if unresolved or unresolved_consts:
    sys.exit(1)

# ---- DM 枚举归属路径（MqAttributeDisplay.constNameFor）----
# 队列 / 队列管理器的整型属性先走这条：同时长常量与属性合法集求交，结果同样拼
# `<CONST>.title`。两张表的常量集**完整可枚举**（就是 constNameFor 的全部值域），
# 所以这里是全量校验，没有近似。
ENUM_TABLES = [
    "ibm-mq-service/src/jvmMain/kotlin/com/mqclient/ibmmq/pcf/MqQueueAttrEnums.kt",
    "ibm-mq-service/src/jvmMain/kotlin/com/mqclient/ibmmq/pcf/MqQMgrAttrEnums.kt",
]
ENUM_ENTRY = re.compile(r'"(MQ[A-Z0-9_]+)"\s+to\s+listOf\(([^)]*)\)', re.S)
CONST_LITERAL = re.compile(r'"(MQ[A-Z0-9_]+)"')

enum_by_attr = {}
for enum_path in ENUM_TABLES:
    for attr, members in ENUM_ENTRY.findall(open(enum_path, encoding="utf-8").read()):
        enum_by_attr.setdefault(attr, []).extend(CONST_LITERAL.findall(members))

enum_consts = {name for members in enum_by_attr.values() for name in members}
missing_enum = sorted(name for name in enum_consts if (name + ".title") not in known)
print("DM 枚举表常量 %d 个（属性 %d 个），缺翻译的 %d 个"
      % (len(enum_consts), len(enum_by_attr), len(missing_enum)))
if missing_enum:
    print("\n!! 枚举归属能返回但翻译表里没有（属性值会显示原始数字）：")
    for name in missing_enum[:40]:
        print("   ", name + ".title")

# ---- valuePrefix 路径（MqAttributeNameMapper.lookupName → 反射 jar）----
# jar 里的常量没法静态枚举，只能查**必要条件**：该前缀至少能匹配到一个有翻译的常量
# （一个都匹配不上 ⇒ lookupName 必然返回 null ⇒ 一定回落原始值）。
# 而只在 valuePrefix **可达**时才要求它：队列 / 队列管理器的解析器先走 DM 枚举
# 归属，属性在表里就遮蔽了 valuePrefix。实测 QueuePropertyGroups 里 9 处手写前缀
# 是错的（MQEVO / MQHA / MQSTS / MQTRG / MQSHA / MQACCTQ / MQCLWLUSEQ，正确族名
# 分别是 MQEVR / MQQA / MQMON / MQTT / MQQA / MQMON / MQCLWL），但对应属性都在
# DM 表里而被遮蔽，属死代码而非 bug —— 哪天属性不在表里了，这里就会报出来。
SHADOWING_FILES = {"QueuePropertyGroups.kt", "QmgrPropertyGroups.kt"}
FIELD_BLOCK = re.compile(r"ObjectPropertyField\((.*?)\n\s*\),", re.S)
translated = {key[:-len(".title")] for key in known if key.endswith(".title")}


def prefix_has_translation(prefix):
    return any(name == prefix or name.startswith(prefix + "_") for name in translated)


prefix_total = 0
dead_prefixes, broken_prefixes = {}, {}
for kt_root in KT_ROOTS:
    for root, _, file_names in os.walk(kt_root):
        for file_name in file_names:
            if not file_name.endswith(".kt"):
                continue
            text = open(os.path.join(root, file_name), encoding="utf-8").read()
            for block in FIELD_BLOCK.findall(text):
                found = re.search(r'valuePrefix = "([A-Z][A-Z0-9_]*)"', block)
                if not found:
                    continue
                prefix_total += 1
                prefix = found.group(1)
                key_match = re.search(r'keys = listOf\("([^"]+)"\)', block)
                attr = key_match.group(1) if key_match else None
                if prefix_has_translation(prefix):
                    continue
                # 属性在 DM 表里 ⇒ 队列 / 队列管理器路径被 constNameFor 遮蔽，不可达
                if file_name in SHADOWING_FILES and attr in enum_by_attr:
                    dead_prefixes.setdefault(prefix, []).append("%s (%s)" % (file_name, attr))
                else:
                    broken_prefixes.setdefault(prefix, []).append("%s (%s)" % (file_name, attr))

print("valuePrefix %d 处，匹配不到任何翻译常量的 %d 处（其中被 DM 表遮蔽的死代码 %d 处）"
      % (prefix_total, sum(len(v) for v in dead_prefixes.values())
         + sum(len(v) for v in broken_prefixes.values()),
         sum(len(v) for v in dead_prefixes.values())))
if dead_prefixes:
    print("\n-- 以下前缀当前不可达（DM 枚举归属先命中），但前缀名本身就是错的，改动时别照抄：")
    for prefix, sites in sorted(dead_prefixes.items()):
        print("   %-12s %s" % (prefix, ", ".join(sites)))
if broken_prefixes:
    print("\n!! 以下 valuePrefix 可达却匹配不到任何翻译常量（属性值会显示原始数字）：")
    for prefix, sites in sorted(broken_prefixes.items()):
        print("   %-12s %s" % (prefix, ", ".join(sites)))

if unresolved or unresolved_consts or missing_enum or broken_prefixes:
    sys.exit(1)
print("OK：静态引用与三条运行时拼接路径全部可解析")
