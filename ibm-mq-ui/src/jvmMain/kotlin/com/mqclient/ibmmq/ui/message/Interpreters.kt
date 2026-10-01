package com.mqclient.ibmmq.ui.message

import com.mqclient.core.log.Log
import com.mqclient.message.interpreter.MessageInterpreter
import com.mqclient.message.interpreter.text.TextInterpreter
import java.util.ServiceLoader

private const val LOG_TAG = "ibm-mq-ui"

/**
 * 解释器注册表：ServiceLoader 装载 + 排序。全部解释器均已拆分为独立模块
 * （text / json / xml / base64 / idoc），本模块不再驻留任何实现。
 *
 *  - 装载逐 provider 容错：单个实现缺失 / 构造失败只跳过并记日志，不拖垮整批
 *    （ModuleRegistry 同款约定）；枚举器本身坏掉（配置损坏）时**停**而不是继续
 *    —— 同一坏迭代器会反复抛同一个错，继续循环等于死循环；
 *  - 按 [MessageInterpreter.sortOrder] 升序稳定排序（同序保持加载顺序），
 *    跨 JAR 的 ServiceLoader 注册顺序不可依赖。
 */
object MessageInterpreters {

    /** ServiceLoader 装载到的全部实现（内置与第三方同一条路）。 */
    fun loadAll(): List<MessageInterpreter> {
        val out = mutableListOf<MessageInterpreter>()
        val iterator = ServiceLoader.load(MessageInterpreter::class.java).iterator()
        while (true) {
            val provider = try {
                if (!iterator.hasNext()) break
                iterator.next()
            } catch (t: Throwable) {
                // hasNext / next 都可能抛 ServiceConfigurationError（Error，不是 Exception）。
                Log.error(LOG_TAG, "failed to load a message interpreter provider, stopping enumeration: $t")
                break
            }
            runCatching { out += provider }.onFailure {
                Log.error(LOG_TAG, "failed to instantiate a message interpreter, skipping: $it")
            }
        }
        return out.sortedBy { it.sortOrder }
    }

    /** 按 id 查找（恢复选中项用；找不到回落文本解释器）。 */
    fun byId(id: String): MessageInterpreter =
        loadAll().firstOrNull { it.id == id } ?: TextInterpreter()
}
