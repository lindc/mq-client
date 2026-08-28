package org.bear.mqclient.core.util;

import org.bear.mqclient.core.config.LocaleHolder;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 国际化文本门面 — 供 core 及各 MQ 插件模块获取当前语言下的显示文本。
 * <p>
 * 文本来源分两层：
 * <ol>
 *   <li><b>插件源</b>：各插件模块自带的 {@code messages_<type>.properties}
 *       （如 {@code messages_kafka.properties}），由 {@link I18nBinder} 在启动时
 *       通过 ServiceLoader 发现全部插件并自动注册。第三方插件只需提供同名 bundle
 *       即可被加载，无需改 core。</li>
 *   <li><b>core 主源</b>：core 的 {@code messages.properties}（中文，默认）/
 *       {@code messages_en.properties}（英文），承载公共文本（common.* 等）。</li>
 * </ol>
 * 查询顺序：插件源（按注册顺序）→ core 主源；均无则返回 key 本身。
 * 插件 bundle 中未提供英文变体时，英文环境回退到该插件的中文基包。
 */
public final class I18n {

    private static volatile MessageSource messageSource;
    private static volatile LocaleHolder localeHolder;
    private static final List<MessageSource> pluginSources = new CopyOnWriteArrayList<>();

    private I18n() {
    }

    /** Spring 启动时调用（见 {@link I18nBinder}），线程安全地绑定实例 */
    public static void bind(MessageSource messageSource, LocaleHolder localeHolder) {
        I18n.messageSource = messageSource;
        I18n.localeHolder = localeHolder;
    }

    /** 注册一个插件翻译源（优先级高于 core 主源）。同一插件重复注册时按新源覆盖。 */
    public static void registerPluginSource(MessageSource source) {
        pluginSources.add(source);
    }

    /** 取当前语言下 key 对应的文本 */
    public static String get(String key) {
        return get(key, (Object[]) null);
    }

    /** 取文本并填充占位符（{0}、{1} ...） */
    public static String get(String key, Object... args) {
        Locale locale = localeHolder != null ? localeHolder.getLocale() : Locale.getDefault();
        for (MessageSource source : pluginSources) {
            try {
                return source.getMessage(key, args, locale);
            } catch (NoSuchMessageException ignored) {
                // 该插件无此 key，尝试下一个源
            }
        }
        if (messageSource == null) return key;
        return messageSource.getMessage(key, args, locale);
    }
}
