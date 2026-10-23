package com.mqclient.core

/**
 * 连接持久化的端口（UI → 核心）。
 *
 * 存在的理由有两个，都很具体：
 *
 *  1. **可测**：表单的落盘链路必须证明「写盘没有压在 UI 线程上」。直连 [ConnectionStore]
 *     这个单例时，测试没有任何插口能观察调用发生在哪个线程、也拦不住它去碰真实的
 *     `~/.mqclient/config.toml`。有了这个端口，测试注入一个记录线程的替身即可。
 *  2. **换实现不改调用方**：将来若把配置换成数据库或远端存储，只换实现。
 *
 * 方法**是阻塞的**，不是 `suspend`：写盘要做「读整份 config.toml → 敏感字段 AES 加密 →
 * 临时文件 → 原子 rename」这一串纯阻塞 IO。调用方必须自己包 `withContext(Dispatchers.IO)`，
 * 这样「在哪个线程上写」是调用点显式声明的，而不是藏在端口实现里 —— 测试也正是据此
 * 断言线程归属。
 *
 * 生产实现见 [Default]（delegates to [ConnectionStore]）。
 */
interface ConnectionPersistence {

    /** 新建一条连接。落盘成功才返回 success（事件广播由 [ConnectionStore] 内部完成）。 */
    fun create(connection: ConnectionInfo): Result<Unit>

    /** 覆盖一条既有连接。core 会换发新 id（old id 废弃、new id 接任）。 */
    fun update(connection: ConnectionInfo): Result<Unit>

    companion object {
        /**
         * 默认实现：直连 [ConnectionStore]。
         *
         * 只做转发，不在这里加线程切换 —— 线程归属由调用点（表单 / 连接菜单）显式声明，
         * 端口保持「在哪个线程被调用就在哪个线程写」的可预测语义。
         */
        val Default: ConnectionPersistence = object : ConnectionPersistence {
            override fun create(connection: ConnectionInfo): Result<Unit> =
                ConnectionStore.create(connection)

            override fun update(connection: ConnectionInfo): Result<Unit> =
                ConnectionStore.update(connection)
        }
    }
}
