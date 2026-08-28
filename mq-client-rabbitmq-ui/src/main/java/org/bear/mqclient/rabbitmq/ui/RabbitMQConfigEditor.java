package org.bear.mqclient.rabbitmq.ui;

import org.bear.mqclient.core.model.MQConnectionConfig;
import org.bear.mqclient.core.ui.FormLabels;
import org.bear.mqclient.core.util.I18n;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/**
 * RabbitMQ 连接配置编辑器。
 * <p>
 * core 将其嵌入「新建连接」对话框。填写完成后将数据写回
 * {@code MQConnectionConfig.properties}。
 */
public class RabbitMQConfigEditor extends VBox {

    private final TextField nameField;
    private final TextField hostField;
    private final TextField portField;
    private final TextField virtualHostField;
    private final TextField usernameField;
    private final PasswordField passwordField;

    private MQConnectionConfig config;

    public RabbitMQConfigEditor() {
        setPadding(new Insets(16));
        setSpacing(8);
        setPrefWidth(400);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);

        nameField = new TextField();
        hostField = new TextField("localhost");
        portField = new TextField("5672");
        virtualHostField = new TextField("/");
        usernameField = new TextField("guest");
        passwordField = new PasswordField();
        passwordField.setText("guest");

        // 绑定字段变化到 config
        nameField.textProperty().addListener((o, a, b) -> { if (config != null) config.setName(b); });
        hostField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("host", b); });
        portField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("port", b); });
        virtualHostField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("virtualHost", b); });
        usernameField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("username", b); });
        passwordField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("password", b); });

        grid.addRow(0, FormLabels.label(I18n.get("common.name")),   nameField);
        grid.addRow(1, FormLabels.label(I18n.get("common.host")),     hostField);
        grid.addRow(2, FormLabels.label(I18n.get("common.port")),     portField);
        grid.addRow(3, FormLabels.label("Virtual Host:"), virtualHostField);
        grid.addRow(4, FormLabels.label(I18n.get("common.username")),   usernameField);
        grid.addRow(5, FormLabels.label(I18n.get("common.password")),     passwordField);

        Button testBtn = new Button(I18n.get("common.testConnection"));
        testBtn.setOnAction(e -> testConnection());

        getChildren().addAll(grid, testBtn);
    }

    /** 由 NewConnectionDialog 调用，注入待编辑的 config 并回填初始值 */
    public void setConfig(MQConnectionConfig cfg) {
        this.config = cfg;
        nameField.setText(cfg.getName() != null ? cfg.getName() : "");
        hostField.setText(cfg.getProperty("host"));
        portField.setText(cfg.getProperty("port"));
        virtualHostField.setText(cfg.getProperty("virtualHost"));
        usernameField.setText(cfg.getProperty("username"));
        passwordField.setText(cfg.getProperty("password"));
        // 字段当前值（含构造时的默认值）同步回 config：未编辑过的字段也要进配置，
        // 否则连接卡片/后续读取拿不到 host/port 等默认值
        cfg.setProperty("host", hostField.getText());
        cfg.setProperty("port", portField.getText());
        cfg.setProperty("virtualHost", virtualHostField.getText());
        cfg.setProperty("username", usernameField.getText());
        cfg.setProperty("password", passwordField.getText());
    }

    private MQConnectionConfig cfg() { return config; }

    private void testConnection() {
        // TODO: 实际测试连接
        Alert alert = new Alert(Alert.AlertType.INFORMATION, I18n.get("common.testNotImplemented"));
        alert.showAndWait();
    }
}
