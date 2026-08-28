package org.bear.mqclient.core.util;

import lombok.RequiredArgsConstructor;
import org.bear.mqclient.core.config.LocaleHolder;
import org.bear.mqclient.core.spi.MQUIProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.Locale;
import java.util.ServiceLoader;

/**
 * 启动时把 MessageSource / LocaleHolder 绑定到 {@link I18n} 静态门面，
 * 并通过 ServiceLoader 发现全部 MQ 插件，注册各插件自带的翻译 bundle
 * （classpath:{@code messages_<type>.properties}，type 为 provider 类型小写，
 * 如 {@code messages_kafka.properties}）。
 * <p>
 * 插件模块不依赖 Spring 容器，只能通过静态门面取文本，因此需要在容器
 * 启动完成后立即完成绑定（@PostConstruct 于单例初始化后触发）。
 */
@Component
@RequiredArgsConstructor
public class I18nBinder {

    private final MessageSource messageSource;
    private final LocaleHolder localeHolder;

    @PostConstruct
    void bind() {
        I18n.bind(messageSource, localeHolder);

        // 发现所有 MQ UI 插件，注册其自带翻译源（可被 ServiceLoader 加载到的均可）
        ServiceLoader.load(MQUIProvider.class)
                .forEach(provider -> I18n.registerPluginSource(
                        pluginBundle(provider.getType())));
    }

    /** 构建插件翻译源：classpath:messages_<type>，UTF-8，不随系统语言回退 */
    private MessageSource pluginBundle(String type) {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:messages_" + type.toLowerCase(Locale.ROOT));
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        return ms;
    }
}
