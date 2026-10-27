package com.mqclient.message.interpreter

import java.util.ServiceLoader
import java.util.logging.Level
import java.util.logging.Logger

private val log = Logger.getLogger("message-interpreter-registry")

/**
 * 解释器的私有 i18n 表描述：与 app-core 的 ModuleI18nTable 同形（base 仅标识，
 * reader 按语言返回扁平 JSON 文本），但**不依赖 app-core** —— registry 保持
 * 零耦合，由宿主（Main.kt）负责包成 ModuleI18nTable 合入全局表。
 */
class InterpreterI18nTable(
    val base: String,
    val reader: suspend (language: String) -> String?,
)

/**
 * 解释器注册表（全项目唯一）：ServiceLoader 装载 + 排序。任何宿主（各 MQ 的
 * UI 模块）只依赖本模块即可获得契约与全部解释器实现，不需要自带注册表。
 *
 *  - 装载逐 provider 容错：单个实现缺失 / 构造失败只跳过并记日志，不拖垮整批
 *    （ModuleRegistry 同款约定）；枚举器本身坏掉（配置损坏）时**停**而不是继续
 *    —— 同一坏迭代器会反复抛同一个错，继续循环等于死循环；
 *  - 按 [MessageInterpreter.sortOrder] 升序稳定排序（同序保持加载顺序），
 *    跨 JAR 的 ServiceLoader 注册顺序不可依赖；
 *  - 实现经 runtimeOnly 聚合进入运行时 classpath（编译期 registry 不依赖任何
 *    实现 —— 新增解释器 = 新模块 + settings include + 这里加一行 runtimeOnly）；
 *  - 日志走 JDK JUL（本模块不依赖任何日志框架 / app-core，保持零耦合）。
 */
object MessageInterpreters {

    /** 解释器私有 i18n 资源的 classpath 根路径（按 id 派生子目录）。 */
    private const val I18N_RESOURCE_DIR = "i18n/interpreter/"

    /** 解释器私有 i18n 表的 base 前缀（全局唯一，供日志与守卫测试识别）。 */
    private const val I18N_BASE_PREFIX = "msg_interp_"

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
                log.log(Level.WARNING, "failed to load a message interpreter provider, stopping enumeration: $t")
                break
            }
            runCatching { out += provider }.onFailure {
                log.log(Level.WARNING, "failed to instantiate a message interpreter, skipping: $it")
            }
        }
        return out.sortedBy { it.sortOrder }
    }

    /**
     * 进程级缓存的解释器清单（[loadAll] 的只读视图）。
     *
     * 为什么需要：消息详情对话框**每开一次**都会读解释器清单，而 [loadAll] 每次都要
     * 走一遍 ServiceLoader（扫 classpath + 实例化全部 provider）。解释器集合在运行期内
     * 不会变（SPI 实现是编译期打进 classpath 的），所以缓存一次就够 —— 把「每开一次
     * 对话框扫一次 classpath」变成「整个进程只扫一次」。
     *
     * 刻意做成**同步**的 `by lazy` 而不是异步加载：异步会让对话框首帧拿到空列表，
     * 于是「二进制内容默认选中 Hex 解释器」这条逻辑要推迟到列表到达后才生效，
     * 用户会先看到文本解释器的结果、再被切成 Hex —— 那是可见的行为回退。
     *
     * 与 [loadAll] 的关系：`loadAll()` 保持原语义（每次重新扫描，测试与诊断用），
     * `all()` 只是它的缓存视图，两者返回**同一批实例**。
     */
    private val cached: List<MessageInterpreter> by lazy { loadAll() }

    /** 缓存版 [loadAll]：首次调用扫描 classpath，之后直接返回同一份列表。 */
    fun all(): List<MessageInterpreter> = cached

    /** 按 id 查找（恢复选中项用）；找不到返回 null，调用方回落 [default]。 */
    fun byId(id: String): MessageInterpreter? =
        all().firstOrNull { it.id == id }

    /**
     * 默认解释器：sortOrder 最小者（内置即 text = 10）。classpath 上一个实现都
     * 没有属于装配错误，宁可尽早抛也不要静默错行为。
     */
    fun default(): MessageInterpreter = all().first()

    /**
     * 各解释器的私有 i18n 表：`base = "msg_interp_<id>"`，reader 从实现自身的
     * classpath 读 `i18n/interpreter/<id>/<lang>.json`（扁平 JSON，格式与全局
     * 字符串表一致）。第三方解释器只要在自己的 jar 放同路径资源即可获得 i18n，
     * 无需任何注册；没有资源则 reader 返回 null —— 调用侧（stringResource）
     * 按 key 原样回落，此时 displayName 应写明文而非键名。
     */
    val i18nTables: List<InterpreterI18nTable> by lazy {
        loadAll().map { impl ->
            InterpreterI18nTable(base = I18N_BASE_PREFIX + impl.id) { language ->
                impl.javaClass.classLoader
                    .getResourceAsStream("$I18N_RESOURCE_DIR${impl.id}/$language.json")
                    ?.readBytes()?.decodeToString()
            }
        }
    }
}
