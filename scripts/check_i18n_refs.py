#!/usr/bin/env python3
"""键引用覆盖检查（i18n 键命名空间的安全网）。

编译和单测抓不到两类问题 —— 它们都不报错，只是界面上直接显示 key 字符串或原始数字：

  1. **静态引用漏改** —— key 改了名但调用点没跟着改；
  2. **运行时拼接的键查不到** —— `valueConstMap` 的常量名 → 策展键 `queue_value_*`
     → 兜底键 `<CONST>.title`，两级都缺就回落原始值（回归过一次：
     `MQIASY_STATUS_*` 缺兜底键，队列管理器状态显示 1/2/3/5 而不是「正在运行」）。

表：ibm_mq 模块表 + ibm_mq_attrs 属性名表 + ui-common 公共表。
后端常量名的解析链必须与 ObjectPropertiesResolver 保持一致（见 CURATED_PREFIXES）。
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
print("OK：静态引用与运行时拼接键全部可解析")
