package org.bear.mqclient.core.ui;

import org.bear.mqclient.core.model.MQConnectionConfig;
import org.bear.mqclient.core.spi.MQUIProvider;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

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

        setTitle("新建连接");
        setHeaderText("选择 MQ 类型");

        // 按钮
        ButtonType nextBtn = new ButtonType("下一步", ButtonBar.ButtonData.NEXT_FORWARD);
        ButtonType cancelBtn = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(nextBtn, cancelBtn);

        // 内容
        this.content = new BorderPane();
        content.setLeft(createTypeSelector());
        content.setCenter(new Label("请选择左侧的 MQ 类型"));

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

        Label title = new Label("MQ 类型");
        title.setStyle("-fx-font-weight: bold; -fx-padding: 0 0 8 0;");

        for (var entry : uiProviders.entrySet()) {
            Button btn = new Button(entry.getValue().getDisplayName());
            btn.setMaxWidth(Double.MAX_VALUE);
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
        setHeaderText(provider.getDisplayName() + " — 连接配置");
    }
}
