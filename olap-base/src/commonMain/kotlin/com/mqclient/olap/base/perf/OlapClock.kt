package com.mqclient.olap.base.perf

/**
 * OLAP 模块的墙上时钟（commonMain 没有 `System.currentTimeMillis`；app-core 的
 * `systemTimeMillis` 是 internal，模板层拿不到 —— 自带一份最小 expect/actual）。
 */
expect fun olapTimeMillis(): Long
