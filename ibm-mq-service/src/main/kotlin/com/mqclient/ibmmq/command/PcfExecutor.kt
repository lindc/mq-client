package com.mqclient.ibmmq.command

/**
 * PCF 命令发送的最小抽象 —— [MqscExecutor] 只依赖它，不碰 IBM MQ 客户端类型，
 * 测试可注入假实现。生产实现是 [MqscPcfSender]。
 */
fun interface PcfExecutor {

    /**
     * 在 [connectionId] 对应的**已建立**连接上执行一条 MQSC 命令。
     *
     * @return MQSC 回显文本行（DISPLAY 类命令的 `AMQ8409: ...` 等）。
     * @throws com.ibm.mq.pcf.PCFException 命令失败（完成码非 OK，reason 为原因码）。
     * @throws IllegalStateException 连接未建立。
     */
    suspend fun executeMqsc(connectionId: String, command: String): List<String>
}
