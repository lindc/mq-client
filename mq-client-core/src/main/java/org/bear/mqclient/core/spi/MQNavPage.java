package org.bear.mqclient.core.spi;

/**
 * 左侧导航中某个 MQ 分组下的一个页面条目。
 * <p>
 * 由各 MQ 的 {@link MQUIProvider#getNavPages()} 提供，core 据此构建可展开分组。
 * {@code pageId} 用于标识页面，点击时传给右侧内容区（目前为「待接入」占位）。
 */
public record MQNavPage(String title, String pageId) {

    public MQNavPage {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title 不能为空");
        }
        if (pageId == null || pageId.isBlank()) {
            throw new IllegalArgumentException("pageId 不能为空");
        }
    }
}
