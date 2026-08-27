package org.bear.mqclient.core;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

/**
 * Spring Boot 配置入口。
 * <p>
 * 仅用 @SpringBootApplication 开启组件扫描与自动配置，
 * 扫描范围限定在 org.bear.mqclient.core（不扫描各 MQ 插件模块，
 * 那些模块仍通过 ServiceLoader SPI 发现，保持插件化解耦）。
 * <p>
 * 注意：未引入任何 web starter，因此这是一个非 Web 应用，
 * Spring Boot 不会启动内嵌服务器。
 */
@SpringBootApplication(scanBasePackages = "org.bear.mqclient.core")
public class CoreConfig {

    /**
     * 国际化消息源。显式声明为 UTF-8，使 messages*.properties 中的
     * 中文无需转为 Unicode 转义即可直接读取。
     */
    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:messages");
        ms.setDefaultEncoding("UTF-8");
        // 回退到基础 messages.properties（中文），而不是 JVM 默认语言对应的
        // 语言包，否则中文会错误地回退到 messages_en
        ms.setFallbackToSystemLocale(false);
        return ms;
    }
}
