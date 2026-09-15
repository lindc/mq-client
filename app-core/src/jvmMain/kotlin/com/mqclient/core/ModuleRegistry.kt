package com.mqclient.core

import com.mqclient.core.event.EventBus
import com.mqclient.core.event.EventNames
import com.mqclient.core.log.Log
import com.mqclient.core.scheduler.JobScheduler
import com.mqclient.core.spi.ServiceContext
import java.util.ServiceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * Loads all business modules registered through Java SPI at startup.
 *
 * Because every `*-service` / `*-ui` module is on the application classpath,
 * [java.util.ServiceLoader] finds their `META-INF/services` registrations
 * without any manual wiring.
 */
object ModuleRegistry {

    /** 统一日志 tag。 */
    private const val LOG_TAG = "module-registry"
    val services: List<ServiceAdapter> by lazy { loadAll(ServiceAdapter::class.java) }

    val uis: List<BusinessUi> by lazy { loadAll(BusinessUi::class.java) }

    /** 各模块的品牌图标（谁的图标归谁的 `*-ui` 模块，见 [ModuleIcon]）。 */
    val icons: List<ModuleIcon> by lazy { loadAll(ModuleIcon::class.java) }

    /**
     * 本次初始化过的模块：`initializeServices` 拿到的全量「适配器 + 它的模块 scope」
     * （含初始化失败的 —— 失败的那个正是需要收尾的），供 [disposeServices] 逆序销毁。
     *
     * scope 必须跟着适配器一起留下来：[ServiceContext] 建完就丢的话，[disposeServices]
     * 手里就只剩适配器，而 [ServiceAdapter.dispose] 默认是空实现 —— 模块忘了自己取消
     * scope，它注册的订阅 / 定时任务就没人收得掉。
     */
    private var initializedServices: List<Pair<ServiceAdapter, CoroutineScope>> = emptyList()

    /**
     * 初始化时拿到的总线：销毁失败要报到错误通道上，而 [disposeServices] 的调用点
     * （应用退出）刻意排在 `CoreModule.shutdown()` 之前，所以这条通道仍然活着。
     */
    private var initializedBus: EventBus? = null

    /**
     * 逐个初始化已发现的 service 模块：给每个模块一个自己的 scope 与 [ServiceContext]，
     * 让它在 [ServiceAdapter.initialize] 里注册自己的事件订阅。
     *
     * 容错策略与 [loadAll] 一致：单个模块初始化失败只跳过它自己（并取消它的 scope，
     * 释放已启动的订阅协程），其余模块照常启动 —— 一个写坏的模块不该拖垮整个应用。
     *
     * 初始化过的模块**连同它的 scope**被记下来，应用退出时由 [disposeServices] 按逆序
     * 调 [ServiceAdapter.dispose] 并取消 scope —— 两个动作成对，别只做其中一个。
     */
    @Synchronized
    fun initializeServices(
        eventBus: EventBus,
        parentScope: CoroutineScope,
        jobScheduler: JobScheduler,
    ): List<ServiceAdapter> {
        val initialized = services.map { adapter ->
            val context = ServiceContext.of(adapter.id, eventBus, parentScope, jobScheduler)
            runCatching { adapter.initialize(context) }
                .onFailure { error ->
                    Log.error(LOG_TAG, "cannot initialize module '${adapter.id}': $error")
                    // 控制台日志打包后看不见，用户至少要在界面里能看到这条。
                    eventBus.emit(
                        name = EventNames.ERROR_OCCURRED,
                        payload = mapOf(
                            "message" to "模块 '${adapter.id}' 初始化失败：${error.message ?: error::class.simpleName}",
                            "moduleId" to adapter.id,
                            "error" to error::class.simpleName,
                        ),
                        source = "core",
                    )
                    context.coroutineScope.cancel()
                }
            adapter to context.coroutineScope
        }

        initializedServices = initialized
        initializedBus = eventBus
        reportUnknownUiServiceIds(eventBus)
        return initialized.map { it.first }
    }

    /**
     * 应用退出：按**初始化逆序**逐个调 [ServiceAdapter.dispose] 并取消它的模块 scope，
     * 让模块释放自己的资源（真实连接 / 会话 / 消费者 / 订阅 / 定时任务）。
     *
     * 调用点必须在 `CoreModule.shutdown()` 与 `DatabaseManager.close()` **之前**
     * （见 app-ui 的 `Main.kt` closeApp）：这时总线与调度器还活着，模块收尾时发的事件
     * 有人收、上报的失败能到界面上；库也还开着，模块可以读完自己最后一份数据。
     *
     * 容错策略与 [initializeServices] 对称：单个模块销毁失败只跳过它自己（日志 + 错误通道），
     * 其余模块照常收尾 —— 一个关不掉的模块不该让别的模块跟着泄漏。
     *
     * 幂等：调用后清空记录，重复调用（菜单「退出」和关窗请求走同一个回调）是 no-op；
     * 从未初始化过时同样是 no-op。
     */
    @Synchronized
    fun disposeServices() {
        val adapters = initializedServices
        val bus = initializedBus
        initializedServices = emptyList()
        initializedBus = null

        disposeAll(adapters) { adapter, error ->
            Log.error(LOG_TAG, "cannot dispose module '${adapter.id}': $error")
            bus?.emit(
                name = EventNames.ERROR_OCCURRED,
                payload = mapOf(
                    "message" to "模块 '${adapter.id}' 销毁失败：${error.message ?: error::class.simpleName}",
                    "moduleId" to adapter.id,
                    "error" to error::class.simpleName,
                ),
                source = "core",
            )
        }
    }

    /**
     * 逆序逐个销毁 —— 后初始化的先销毁，它更可能压在先初始化的模块之上。
     * 单个模块抛出的异常交给 [onFailure] 收走，不中断循环（否则第一个坏模块就能让
     * 后面所有模块的资源全部泄漏）。抽成纯函数是为了可测（见 ModuleRegistryTest）。
     *
     * 每个模块两步：先 [ServiceAdapter.dispose]（这时 scope 还活着，模块还能发事件、
     * 起协程收尾），再取消 scope。取消放在 `runCatching` **外面** —— dispose 抛错的
     * 那条也一定要把 scope 收掉，否则它注册的订阅和定时任务会一直挂着。
     */
    internal fun disposeAll(
        initialized: List<Pair<ServiceAdapter, CoroutineScope>>,
        onFailure: (ServiceAdapter, Throwable) -> Unit,
    ) {
        initialized.asReversed().forEach { (adapter, scope) ->
            runCatching { adapter.dispose() }.onFailure { error -> onFailure(adapter, error) }
            scope.cancel()
        }
    }

    /**
     * UI 半与服务半靠 `BusinessUi.serviceId == ServiceAdapter.id` 的字符串约定对上号
     * （两个 Gradle 模块互不依赖，编译期帮不上忙），拼错时界面只会静默少一块。
     * 启动时对一遍：对不上的同时上报错误通道 + 日志，让名字错误在启动时暴露。
     *
     * 反方向（service 没有对应 UI）**不算错误** —— 模块可以只有后台能力、没有界面。
     */
    private fun reportUnknownUiServiceIds(eventBus: EventBus) {
        val known = services.mapTo(mutableSetOf()) { it.id }
        unknownUiServiceIds(known, uis.map { it.serviceId }).forEach { serviceId ->
            val message = "BusinessUi.serviceId '$serviceId' 对不上任何 ServiceAdapter.id，它的界面永远不会显示"
            Log.error(LOG_TAG, message)
            eventBus.emit(
                name = EventNames.ERROR_OCCURRED,
                payload = mapOf(
                    "message" to message,
                    "moduleId" to "core",
                ),
                source = "core",
            )
        }
    }

    /**
     * 返回 [uiServiceIds] 里对不上任何 [knownServiceIds] 的项（保持顺序、去重）。
     * 抽成纯函数是为了可测（见 ModuleRegistryTest）。
     */
    internal fun unknownUiServiceIds(knownServiceIds: Set<String>, uiServiceIds: List<String>): List<String> =
        uiServiceIds.filter { it !in knownServiceIds }.distinct()

    /**
     * 按 [ServiceAdapter.category] 把服务模块分组（新建连接对话框的 tab 数据源）。
     * 类目完全由 SPI 扫描得出 —— 新模块带新 category 时 tab 自动出现，无需改任何
     * 调用方。抽成纯函数是为了可测（见 ModuleRegistryTest）。
     */
    fun groupByCategory(services: List<ServiceAdapter>): Map<String, List<ServiceAdapter>> =
        services.groupBy { it.category }

    /** [loadFrom] with the real [ServiceLoader] iterator. */
    private fun <T : Any> loadAll(type: Class<T>): List<T> = loadFrom(type) { ServiceLoader.load(type).iterator() }

    /**
     * Loads providers one by one.
     *
     * [ServiceLoader] aborts the whole iteration the moment a single provider
     * fails (missing class, no public no-arg constructor, throwing `init`, …),
     * which used to cost us every other module. Here a broken module is skipped
     * and reported instead, so the rest of the app still comes up.
     *
     * `hasNext()` 同样会炸（配置文件损坏 / 编码非法时抛
     * `ServiceConfigurationError`），而且它是 `Error` 不是 `Exception`，
     * 放在 `runCatching` 之外会一路逃出 `by lazy` —— `lazy` 不缓存异常，
     * 之后每次访问都会重抛。所以两步都要兜住；枚举失败时**停**而不是继续：
     * 同一个坏迭代器只会反复抛同一个错，继续循环等于死循环。
     *
     * [iteratorFactory] 是可注入的迭代器工厂：真实的 SPI 故障（配置文件损坏）
     * 很难在测试里稳定复现，测试靠它灌入一个会抛错的假迭代器。
     */
    internal fun <T : Any> loadFrom(type: Class<T>, iteratorFactory: () -> Iterator<T>): List<T> = buildList {
        val providers = iteratorFactory()
        while (true) {
            val hasNext = runCatching { providers.hasNext() }
                .onFailure {
                    Log.error(LOG_TAG, "cannot enumerate ${type.simpleName} providers: $it")
                }
                .getOrNull() ?: break
            if (!hasNext) break
            runCatching { providers.next() }
                .onFailure {
                    // ServiceConfigurationError is an Error, not an Exception.
                    Log.error(LOG_TAG, "cannot load ${type.simpleName} provider: $it")
                }
                .onSuccess(::add)
        }
    }
}
