#!/usr/bin/env python3
"""键引用覆盖检查（i18n 键命名空间的安全网）。

编译和单测抓不到下面这些 —— 它们都不报错，只是界面上直接显示 key 字符串或原始数字：

  1. **静态引用漏改** —— key 改了名但调用点没跟着改；
  2. **运行时拼接的键查不到**，共三条拼法（IBM MQ 属性表专有）：
     - `valueConstMap` 常量名 → 策展键 `queue_value_*` → 兜底键 `<CONST>.title`
     - DM 枚举归属 `MqAttributeDisplay.constNameFor` → `<CONST>.title`
       （队列 / 队列管理器先走这条，命中即遮蔽 `valuePrefix`）
     - `valuePrefix` → `MqAttributeNameMapper.lookupName` 反射 jar → `<CONST>.title`

**扫描范围是全仓**：所有模块的 `src/` 下的 `.kt` 与各模块 `composeResources/files/i18n/
*_<lang>.json` 都参与（此前只扫 ibm-mq-ui / ibm-mq-service，Kafka / RabbitMQ /
RocketMQ / app-ui / ui-common / topology-ui 里的键写错只会在界面上冒出裸 key）。

IBM MQ 的三条运行时拼接路径仍限定在 ibm-mq 的两个源码根（常量名的解析链必须与
ObjectPropertiesResolver / QueuePropertyGroups 保持一致，见 CURATED_PREFIXES 与
SHADOWING_FILES）。
"""
import glob
import json
import os
import re
import sys

LANG = sys.argv[1] if len(sys.argv) > 1 else "zh-CN"
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
COMMON_TABLE = "ui-common/src/commonMain/composeResources/files/i18n/strings_%s.json" % LANG

# 静态引用 + 键表的扫描范围：仓库根下每个模块的 src/（跳过 build 产物）。
SCAN_ROOTS = sorted(
    os.path.join(REPO, path, "src")
    for path in os.listdir(REPO)
    if os.path.isdir(os.path.join(REPO, path, "src"))
)

# 所有模块私有表 + 公共表 + 属性名表（合成一张「已知键」集合）。
def table_files(lang):
    return sorted(
        glob.glob(os.path.join(REPO, "*", "src", "*", "composeResources", "files", "i18n", "*_%s.json" % lang))
    )


# 中文是**基底表**，英文只覆盖它自己提供的键（见 LanguageResources.load）。
# 所以「英文缺键」不是 bug —— 运行时会回落到中文，界面不会冒裸 key。
# 已知键集合必须复刻这条链（模块中文 → 模块英文），否则 en-US 检查会把
# 合法的回落全部当成错误（实测：142 个 ibm_mq_qmgr/queue_prop 描述只有中文）。
TABLE_FILES = table_files(LANG)
BASE_TABLE_FILES = table_files("zh-CN") if LANG != "zh-CN" else []

# IBM MQ 运行时拼接路径的扫描根（常量名解析链只在这里成立）。
KT_ROOTS = [os.path.join(REPO, p) for p in ("ibm-mq-ui/src", "ibm-mq-service/src")]

# 静态引用点：直接调用 + 属性组数据里的键字段 + 卡片操作 tooltip 等
# 注意别收 `label = "..."`：Compose 的 rememberInfiniteTransition(label=) /
# animateFloat(label=) 也长这样，会误抓（edgeFlow / flowGap）。
PATTERNS = [
    re.compile(r'stringResource\("([^"$]+)"\)'),
    re.compile(r'\b(nameKey|descKey|valueKey|valueFallbackKey|titleKey|tooltip)\s*=\s*"([^"$]+)"'),
]
# 属性名表（ibm_mq_attrs_*.json）的键：`MQXXX.title`，常量名自带命名空间，豁免
ATTR_KEY = re.compile(r"^(MQ[A-Z0-9_]+)\.title$")

# 键的合法形状：小写字母 / 数字 / 下划线 / 点。
# 用于滤掉两类误报：KDoc 里举例的 `stringResource("…")`（省略号不是 ASCII），
# 以及拼接表达式漏进正则的碎片。
KEY_SHAPE = re.compile(r"^[a-z0-9][a-z0-9_.]*$")

# 测试源集不复扫：测试**故意**引用不存在的键（验证回落语义），
# 例如 ModuleI18nMergeTest 的 nav_not_a_real_key / LanguageResourcesTest 的
# some_missing_key。把它们算进覆盖率只会逼着往表里加假键。
TEST_DIR_NAMES = {"jvmTest", "commonTest", "jvmMainTest"}


def is_test_source(path, root):
    parts = os.path.relpath(path, root).split(os.sep)
    return any(part in TEST_DIR_NAMES for part in parts)

module = set()
for table_file in BASE_TABLE_FILES + TABLE_FILES:
    module.update(json.load(open(table_file, encoding="utf-8")))
common = set(json.load(open(os.path.join(REPO, COMMON_TABLE), encoding="utf-8")))
attrs = set()
for table_file in BASE_TABLE_FILES + TABLE_FILES:
    if "ibm_mq_attrs" in table_file:
        attrs.update(json.load(open(table_file, encoding="utf-8")))

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
for kt_root in SCAN_ROOTS:
    for root, _, names in os.walk(kt_root):
        for name in names:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(root, name)
            if is_test_source(path, kt_root):
                continue
            text = open(path, encoding="utf-8").read()
            for pattern in PATTERNS:
                for match in pattern.finditer(text):
                    key = match.groups()[-1]
                    if not KEY_SHAPE.match(key):
                        continue
                    referenced.setdefault(key, []).append(os.path.relpath(path, REPO))

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
    for attr, members in ENUM_ENTRY.findall(open(os.path.join(REPO, enum_path), encoding="utf-8").read()):
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
