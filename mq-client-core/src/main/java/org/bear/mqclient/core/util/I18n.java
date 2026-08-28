package org.bear.mqclient.core.util;

import org.bear.mqclient.core.config.LocaleHolder;

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 国际化文本门面 — 供 core 及各 MQ 插件模块获取当前语言下的显示文本。
 * <p>
 * 文本来源分两层：
 * <ol>
 *   <li><b>插件源</b>：各插件模块自带的 {@code messages_<type>.properties}
 *       （如 {@code messages_kafka.properties}），由 {@link I18nBinder} 启动时
 *       通过 ServiceLoader 发现全部插件并自动注册 basename。</li>
 *   <li><b>core 主源</b>：core 的 {@code messages.properties}（中文，默认）/
 *       {@code messages_en.properties}（英文），承载公共文本（common.* 等）。</li>
 * </ol>
 * 查询顺序：插件源（按注册顺序）→ core 主源；均无则返回 key 本身。
 * <p>
 * 基于 JDK {@link ResourceBundle} 实现（Java 9+ 属性文件默认 UTF-8），
 * 无第三方框架依赖；插件 bundle 未提供英文变体时，英文环境回退到该插件的中文基包。
 */
public final class I18n {

    /** core 公共 bundle 的 basename */
    private static final String CORE_BUNDLE = "messages";

    private static final List<String> pluginBundles = new CopyOnWriteArrayList<>();
    private static volatile LocaleHolder localeHolder;

    private I18n() {
    }

    /** 启动时调用（见 {@link I18nBinder}），线程安全地绑定语言持有者 */
    public static void bind(LocaleHolder localeHolder) {
        I18n.localeHolder = localeHolder;
    }

    /** 注册一个插件 bundle 的 basename（优先级高于 core 主源） */
    public static void registerPluginBundle(String baseName) {
        pluginBundles.add(baseName);
    }

    /** 取当前语言下 key 对应的文本 */
    public static String get(String key) {
        return get(key, (Object[]) null);
    }

    /** 取文本并填充占位符（{0}、{1} ...） */
    public static String get(String key, Object... args) {
        Locale locale = localeHolder != null ? localeHolder.getLocale() : Locale.getDefault();

        for (String base : pluginBundles) {
            ResourceBundle bundle = load(base, locale);
            if (bundle != null && bundle.containsKey(key)) {
                return format(bundle.getString(key), args);
            }
        }

        ResourceBundle core = load(CORE_BUNDLE, locale);
        if (core != null && core.containsKey(key)) {
            return format(core.getString(key), args);
        }
        return key;
    }

    /**
     * 按 basename + locale 加载 bundle；不存在时返回 null（如插件未提供翻译文件）。
     * <p>
     * 使用 NoFallbackControl：禁止回退到 JVM 系统默认 locale（否则 zh_CN 在英文系统上
     * 会被解析成 messages_en），只沿目标 locale 的候选链查找（zh_CN → zh → 基包），
     * 与历史配置 fallbackToSystemLocale=false 语义一致。
     */
    private static ResourceBundle load(String baseName, Locale locale) {
        try {
            return ResourceBundle.getBundle(baseName, locale,
                    ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT));
        } catch (MissingResourceException e) {
            return null;
        }
    }

    private static String format(String pattern, Object[] args) {
        if (args == null || args.length == 0) return pattern;
        return MessageFormat.format(pattern, args);
    }
}
