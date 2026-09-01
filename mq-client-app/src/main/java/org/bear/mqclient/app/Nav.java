package org.bear.mqclient.app;

import javafx.scene.Node;

import java.util.Locale;
import java.util.Objects;

/**
 * 左侧导航树的一个节点。
 * <p>
 * {@code pageId == null} 表示分组（只负责展开/折叠），否则表示可打开的页面。
 */
record Nav(String title, Node graphic, String pageId) {

    static final Nav ROOT = new Nav("ROOT", null, null);

    Nav {
        Objects.requireNonNull(title, "title");
    }

    boolean isGroup() {
        return pageId == null;
    }

    boolean matches(String filter) {
        return title.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
    }
}
