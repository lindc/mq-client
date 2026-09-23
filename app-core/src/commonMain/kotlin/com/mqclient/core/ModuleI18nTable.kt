package com.mqclient.core

/**
 * 一个业务模块的**私有 i18n 字符串表**：模块在自己的 composeResources 里放
 * `files/i18n/<base>_<lang>.json`，并经 [reader] 用**模块自己生成的 `Res`** 读取。
 *
 * 为什么是读取器而不是路径：Compose 的运行时资源**按库的包名隔离**
 * （实际打包位置是 `composeResources/<库包名>/files/...`），ui-common 的 `Res`
 * 读不到别的模块的文件 —— 所以读取必须由模块自己做，宿主只负责合并结果。
 *
 * [base] 全应用内唯一（约定用模块 id 的下划线形式，如 `ibm_mq`），只作日志 /
 * 调试标识，不参与路径计算。[reader] 收语言代码（`"zh-CN"` / `"en-US"`），
 * 返回 JSON 文本；文件不存在返回 null（或抛错，宿主会兜住并记日志）。
 */
class ModuleI18nTable(
    val base: String,
    val reader: suspend (language: String) -> String?,
)
