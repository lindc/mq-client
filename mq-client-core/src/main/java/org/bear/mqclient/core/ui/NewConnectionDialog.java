package org.bear.mqclient.core.ui;

import org.bear.mqclient.core.model.MQConnectionConfig;
import org.bear.mqclient.core.spi.MQUIProvider;
import org.bear.mqclient.core.util.I18n;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.*;

/**
 * 新建连接对话框。
 * <p>
 * 第一步：选择 MQ 类型（core 提供按钮列表）
 * 第二步：由 MQ 客户端的 createConfigEditor() 接管
 */
public class NewConnectionDialog extends Dialog<MQConnectionConfig> {

    private final Map<String, MQUIProvider> uiProviders;
    private final BorderPane content;

    private String selectedType;
    private Node configEditor;
    private MQConnectionConfig config;

    public NewConnectionDialog(Map<String, MQUIProvider> uiProviders) {
        this.uiProviders = uiProviders;

        // 模态：WINDOW_MODAL + 依附主窗口。APPLICATION_MODAL 在 Wayland/XWayland 下
        // 依赖全局 grab，窗口可能在 show 后立刻被隐藏（见 AboutDialog 的同类注释）。
        initModality(Modality.WINDOW_MODAL);
        for (Window w : Stage.getWindows()) {
            if (w.isShowing()) {
                initOwner(w);
                break;
            }
        }

        setTitle(I18n.get("connection.dialog.title"));
        setHeaderText(I18n.get("connection.dialog.selectType"));

        // 按钮
        ButtonType nextBtn = new ButtonType(I18n.get("common.next"), ButtonBar.ButtonData.NEXT_FORWARD);
        ButtonType cancelBtn = new ButtonType(I18n.get("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(nextBtn, cancelBtn);

        // 内容
        this.content = new BorderPane();
        content.setLeft(createTypeSelector());
        content.setCenter(new Label(I18n.get("connection.dialog.prompt")));

        getDialogPane().setContent(content);
        getDialogPane().setPrefSize(650, 450);

        // 结果转换
        setResultConverter(btn -> {
            if (btn == nextBtn && config != null) {
                return config;
            }
            return null;
        });
    }

    private Node createTypeSelector() {
        VBox box = new VBox(8);
        box.setPadding(new Insets(12));
        box.setPrefWidth(180);

        Label title = new Label(I18n.get("connection.dialog.mqType"));
        title.setStyle("-fx-font-weight: bold; -fx-padding: 0 0 8 0;");

        for (var entry : uiProviders.entrySet()) {
            Button btn = new Button(entry.getValue().getDisplayName());
            // 固定宽度 + 文字左对齐：各 MQ 显示名长度不同（IBM MQ / ActiveMQ Artemis ...），
            // 切换时保持布局与文字起点一致，避免左右晃动
            btn.setPrefWidth(180);
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setAlignment(Pos.CENTER_LEFT);
            String type = entry.getKey();
            MQUIProvider provider = entry.getValue();
            btn.setOnAction(e -> selectType(type, provider));
            box.getChildren().add(btn);
        }

        return box;
    }

    private void selectType(String type, MQUIProvider provider) {
        this.selectedType = type;

        // 创建配置编辑器
        this.configEditor = provider.createConfigEditor();
        // MQ 客户端需要自己将配置编辑器绑定到 config 对象上
        // 这里约定 MQ 客户端通过 configEditor 的 userData 或直接修改传入的 config
        this.config = new MQConnectionConfig(null);
        config.setType(type);
        config.setName("New " + provider.getDisplayName());

        // 将 config 传给 configEditor（由 MQ 实现自行绑定）
        if (configEditor != null) {
            provider.applyConfig(configEditor, config);
        }

        content.setCenter(configEditor);
        setHeaderText(I18n.get("connection.dialog.header", provider.getDisplayName()));
    }
}
