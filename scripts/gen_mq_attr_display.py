#!/usr/bin/env python3
r"""Generate IBM MQ display tables from the locally installed MQ Explorer.

Sources (read-only, from the Eclipse MQ Explorer plugins):
  - com.ibm.mq.explorer.core_*.jar      -> com/ibm/mq/explorer/core/internal/base/common.properties
                                           (keys `MQXXX.title` = friendly names for attributes
                                            AND enum constants, English)
  - com.ibm.mq.explorer.core.nl1_*.jar  -> .../common_zh_CN.properties (same keys, zh-CN, \uXXXX escaped)
  - com.ibm.mq.explorer.core_*.jar      -> xml/DmQueue.xml (which attributes are ENUM and which
                                            MQ constants are legal for each)

Outputs (checked in, do not edit by hand):
  - ibm-mq-ui/src/commonMain/composeResources/files/i18n/ibm_mq_attrs_en-US.json
  - ibm-mq-ui/src/commonMain/composeResources/files/i18n/ibm_mq_attrs_zh-CN.json
    (module i18n table base `ibm_mq_attrs`, keys are the raw `MQXXX.title`)
  - ibm-mq-service/src/jvmMain/kotlin/com/mqclient/ibmmq/pcf/MqQueueAttrEnums.kt
    (attr -> legal enum constant names, used to disambiguate int values)

Usage: python3 scripts/gen_mq_attr_display.py [--plugins /opt/software/IDE/eclipse/plugins]
"""

import argparse
import codecs
import json
import re
import zipfile
from pathlib import Path
from xml.etree import ElementTree

REPO = Path(__file__).resolve().parent.parent

TITLE_KEY = re.compile(r"^MQ[A-Z0-9_]+\.title$")
CONST_NAME = re.compile(r"^MQ[A-Z0-9_]+$")


def read_jar_text(jar: Path, member: str) -> str:
    with zipfile.ZipFile(jar) as zf:
        return zf.read(member).decode("iso-8859-1")


def parse_properties(text: str) -> dict[str, str]:
    """Minimal java.util.Properties parser: comments, continuations, escapes."""
    props: dict[str, str] = {}
    logical = ""
    pending = False
    for raw in text.split("\n"):
        line = raw.strip()
        if pending:
            logical += line
        else:
            if not line or line[0] in "#!":
                continue
            logical = line
        pending = _ends_with_odd_backslashes(logical)
        if pending:
            logical = logical[:-1]
            continue
        key, _, value = _split_key_value(logical)
        props[key] = value
        logical = ""
    return props


def _ends_with_odd_backslashes(s: str) -> bool:
    n = 0
    while n < len(s) and s[-1 - n] == "\\":
        n += 1
    return n % 2 == 1


def _split_key_value(line: str) -> tuple[str, str, str]:
    for i, ch in enumerate(line):
        if ch in "=:" and (i == 0 or line[i - 1] != "\\"):
            return _unescape(line[:i]), "=", _unescape(line[i + 1 :].lstrip())
        if ch in " \t" and (i == 0 or line[i - 1] != "\\"):
            rest = line[i:].lstrip()
            if rest[:1] in "=:":
                return _unescape(line[:i]), "=", _unescape(rest[1:].lstrip())
            return _unescape(line[:i]), " ", _unescape(rest)
    return _unescape(line), " ", ""


def _unescape(s: str) -> str:
    out = []
    i = 0
    while i < len(s):
        ch = s[i]
        if ch != "\\" or i + 1 >= len(s):
            out.append(ch)
            i += 1
            continue
        nxt = s[i + 1]
        if nxt == "u" and i + 6 <= len(s):
            out.append(chr(int(s[i + 2 : i + 6], 16)))
            i += 6
        elif nxt == "t":
            out.append("\t"); i += 2
        elif nxt == "n":
            out.append("\n"); i += 2
        elif nxt == "r":
            out.append("\r"); i += 2
        elif nxt == "f":
            out.append("\f"); i += 2
        else:  # \\ \/ \: \= \  and any unknown escape -> literal char
            out.append(nxt); i += 2
    return "".join(out)


def extract_titles(core_jar: Path, nl1_jar: Path) -> tuple[dict, dict]:
    en_src = parse_properties(
        read_jar_text(core_jar, "com/ibm/mq/explorer/core/internal/base/common.properties")
    )
    zh_src = parse_properties(
        read_jar_text(nl1_jar, "com/ibm/mq/explorer/core/internal/base/common_zh_CN.properties")
    )
    en = {k: v for k, v in en_src.items() if TITLE_KEY.match(k)}
    # zh 缺键用英文兜底，保证中文表自包含（loader 在 zh 模式下不加载 en 表）。
    zh = {k: zh_src.get(k, v) for k, v in en.items()}
    return en, zh


def extract_queue_enums(core_jar: Path) -> dict[str, list[str]]:
    with zipfile.ZipFile(core_jar) as zf:
        xml = zf.read("xml/DmQueue.xml").decode("utf-8")
    root = ElementTree.fromstring(xml)
    enums: dict[str, list[str]] = {}
    for attr in root.iter("attribute"):
        name = attr.get("name", "")
        if not name.startswith("CMQC."):  # PseudoPCF 等浏览器内部伪属性跳过
            continue
        if attr.get("type") != "ENUM":
            continue
        consts = [
            e.get("value")
            for e in attr.findall("enum")
            if e.get("value") and CONST_NAME.match(e.get("value"))
        ]
        if consts:
            enums.setdefault(name.removeprefix("CMQC."), []).extend(consts)
    return enums


def write_json(data: dict, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(data, ensure_ascii=False, indent=0, sort_keys=True), encoding="utf-8"
    )
    print(f"wrote {path} ({len(data)} keys)")


def write_enum_kt(enums: dict[str, list[str]], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "package com.mqclient.ibmmq.pcf",
        "",
        "/**",
        " * 队列属性 -> 合法枚举常量名（生成文件，勿手改）。",
        " * 来源：MQ Explorer `com.ibm.mq.explorer.core` 的 `xml/DmQueue.xml`，",
        " * 由 `scripts/gen_mq_attr_display.py` 生成；键为 PCF 属性符号名",
        " * （如 `MQIA_Q_TYPE`），值为该属性 `type=\"ENUM\"` 收录的全部 MQ 常量名。",
        " * 用途：整型属性值反查出的同值常量名与本表求交，确定唯一显示常量",
        " * （见 [MqAttributeDisplay.constNameFor]）。",
        " */",
        "internal object MqQueueAttrEnums {",
        "    val byAttr: Map<String, List<String>> = mapOf(",
    ]
    for attr in sorted(enums):
        consts = ", ".join(f'"{c}"' for c in dict.fromkeys(enums[attr]))
        lines.append(f'        "{attr}" to listOf({consts}),')
    lines += ["    )", "}"]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {path} ({len(enums)} attrs)")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--plugins", default="/opt/software/IDE/eclipse/plugins")
    args = ap.parse_args()
    plugins = Path(args.plugins)
    core_jar = sorted(plugins.glob("com.ibm.mq.explorer.core_10*.jar"))[0]
    nl1_jar = sorted(plugins.glob("com.ibm.mq.explorer.core.nl1_10*.jar"))[0]

    en, zh = extract_titles(core_jar, nl1_jar)
    res = REPO / "ibm-mq-ui/src/commonMain/composeResources/files/i18n"
    write_json(en, res / "ibm_mq_attrs_en-US.json")
    write_json(zh, res / "ibm_mq_attrs_zh-CN.json")

    enums = extract_queue_enums(core_jar)
    write_enum_kt(
        enums,
        REPO / "ibm-mq-service/src/jvmMain/kotlin/com/mqclient/ibmmq/pcf/MqQueueAttrEnums.kt",
    )


if __name__ == "__main__":
    main()
