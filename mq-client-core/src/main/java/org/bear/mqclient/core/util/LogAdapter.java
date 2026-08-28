package org.bear.mqclient.core.util;

import lombok.extern.slf4j.Slf4j;

import java.util.Locale;

/**
 * SLF4J 日志适配 — 供 core 及各 MQ 插件模块写日志/记录。
 * <p>
 * 与直接使用 SLF4J Logger 的区别：每条记录带 source 标签（如 "KAFKA"、"IBM_MQ"），
 * 便于在日志文件中按插件来源过滤。日志输出位置见 core 的 logback.xml
 * （控制台 + {@code ~/.mqclient/logs/mq-client.log} 按天滚动）。
 */
@Slf4j
public final class LogAdapter {

    private LogAdapter() {
    }

    /**
     * 按级别字符串记录（trace/debug/info/warn/error，忽略大小写；未知级别视为 info）。
     *
     * @param source  来源标签，如 "KAFKA"、"IBM_MQ"
     * @param level   级别字符串
     * @param message 消息内容
     */
    public static void record(String source, String level, String message) {
        String msg = prefix(source, message);
        switch (level == null ? "info" : level.toLowerCase(Locale.ROOT)) {
            case "trace" -> log.trace(msg);
            case "debug" -> log.debug(msg);
            case "warn" -> log.warn(msg);
            case "error" -> log.error(msg);
            default -> log.info(msg);
        }
    }

    public static void info(String source, String message) {
        log.info(prefix(source, message));
    }

    public static void warn(String source, String message) {
        log.warn(prefix(source, message));
    }

    public static void error(String source, String message) {
        log.error(prefix(source, message));
    }

    public static void error(String source, String message, Throwable t) {
        log.error(prefix(source, message), t);
    }

    private static String prefix(String source, String message) {
        return "[" + source + "] " + message;
    }
}
