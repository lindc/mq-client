package com.mqclient.uicommon.file

/**
 * Windows 路径统一成正斜杠：ktoml 0.7.1 的转义器对 `\U`（C:\Users）、`\t`（\trust）
 * 这类「恰好构成 TOML 转义前缀」的序列不转义，写出的 config.toml 自己都解析不了。
 * Java 在 Windows 上原生接受正斜杠（FileInputStream / JSSE / FileDialog 均可），
 * 从边界上避开整个转义问题。文件选择器填入的路径一律先过这里再落草稿。
 */
fun normalizePath(path: String): String = path.replace('\\', '/')
