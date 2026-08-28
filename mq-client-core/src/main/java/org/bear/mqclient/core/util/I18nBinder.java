package org.bear.mqclient.core.util;

import org.bear.mqclient.core.config.LocaleHolder;
import org.bear.mqclient.core.spi.MQUIProvider;

import java.util.Locale;
import java.util.ServiceLoader;

/**
 * 启动装配：把 {@link LocaleHolder} 绑定到 {@link I18n} 门面，
 * 并通过 ServiceLoader 发现全部 MQ 插件，注册各插件自带的翻译 bundle
 * （classpath:{@code messages_<type>.properties}，type 为 provider 类型小写，
 * 如 {@code messages_kafka.properties}）。
 * <p>
 * 无 Spring 依赖：由应用入口（App.main / MainApp.main）在 UI 创建前
 * 显式调用 {@link #bind(LocaleHolder)}。
 */
public final class I18nBinder {

    private I18nBinder() {
    }

    /** 绑定 core 主源 + 注册全部插件 bundle，需在 UI 创建前调用一次 */
    public static void bind(LocaleHolder localeHolder) {
        I18n.bind(localeHolder);

        // 发现所有 MQ UI 插件，注册其自带翻译源（可被 ServiceLoader 加载到的均可）
        ServiceLoader.load(MQUIProvider.class)
                .forEach(provider -> I18n.registerPluginBundle(
                        "messages_" + provider.getType().toLowerCase(Locale.ROOT)));
    }
}
