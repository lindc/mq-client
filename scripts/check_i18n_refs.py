#!/usr/bin/env python3
"""键引用覆盖检查（命名空间重命名后的安全网）。

编译和单测都抓不到「key 改了名但引用点漏改」—— 那种情况不报错，只是界面上显示
key 字符串本身。这里把 ibm-mq-ui 源码里所有**静态可解析**的 i18n 键引用抽出来，
逐个断言能在这两张表之一里解析到：

  - ibm_mq_* 模块表（--lang 指定，默认 zh-CN，它是超集）
  - ui-common 公共表（nav_* / common_* / menu_* 等宿主词汇）

运行时拼出来的键（含 `$`）不做字面量校验，由 namespace 脚本的 spec 断言覆盖。
"""
import json
import os
import re
import sys

LANG = sys.argv[1] if len(sys.argv) > 1 else "zh-CN"
MODULE_TABLE = "ibm-mq-ui/src/commonMain/composeResources/files/i18n/ibm_mq_%s.json" % LANG
COMMON_TABLE = "ui-common/src/commonMain/composeResources/files/i18n/strings_%s.json" % LANG
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
print("OK：全部静态引用都能解析")
