package org.bear.mqclient.core.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;

/**
 * 表单 label 工具 — 统一 label 列宽度并右对齐。
 * <p>
 * 各 MQ 配置编辑器的字段 label 长短不一（如 "用户名:" 与 "Consumer Group ID:"），
 * 若 label 不固定宽度，输入框的起始位置会随 label 长度漂移。
 * 统一使用 {@link #label(String)} 创建 label 即可保证同一表单内
 * （以及切换 MQ 时）所有输入框起点一致。
 */
public final class FormLabels {

    /** label 列统一宽度（px），覆盖现有各 MQ 编辑器中最长的 label */
    public static final double WIDTH = 140;

    private FormLabels() {
    }

    /** 创建固定宽度、右对齐的表单 label */
    public static Label label(String text) {
        Label l = new Label(text);
        l.setMinWidth(WIDTH);
        l.setAlignment(Pos.CENTER_RIGHT);
        return l;
    }
}
