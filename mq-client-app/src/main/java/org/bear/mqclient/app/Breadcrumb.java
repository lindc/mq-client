package org.bear.mqclient.app;

import atlantafx.base.theme.Styles;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

/**
 * 顶部面包屑导航：显示当前位置路径（如「Kafka / 主题」）。
 * 点击左侧导航或中间 tab 时调用 {@link #setPath(String...)} 更新。
 */
final class Breadcrumb extends HBox {

    Breadcrumb() {
        getStyleClass().add("breadcrumb");
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(0, 0, 0, 8));
    }

    void setPath(String... segments) {
        getChildren().clear();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                var sep = new Label("/");
                sep.getStyleClass().addAll("sep", Styles.TEXT_MUTED);
                getChildren().add(sep);
            }
            var label = new Label(segments[i]);
            label.getStyleClass().add(i == segments.length - 1 ? "current" : "crumb");
            getChildren().add(label);
        }
    }
}
