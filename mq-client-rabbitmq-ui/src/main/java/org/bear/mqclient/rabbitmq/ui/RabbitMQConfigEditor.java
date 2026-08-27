package org.bear.mqclient.rabbitmq.ui;

import org.bear.mqclient.core.model.MQConnectionConfig;
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

        grid.addRow(0, new Label("连接名:"),   nameField);
        grid.addRow(1, new Label("主机:"),     hostField);
        grid.addRow(2, new Label("端口:"),     portField);
        grid.addRow(3, new Label("Virtual Host:"), virtualHostField);
        grid.addRow(4, new Label("用户名:"),   usernameField);
        grid.addRow(5, new Label("密码:"),     passwordField);

        Button testBtn = new Button("测试连接");
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
    }

    private MQConnectionConfig cfg() { return config; }

    private void testConnection() {
        // TODO: 实际测试连接
        Alert alert = new Alert(Alert.AlertType.INFORMATION, "测试连接功能待实现");
        alert.showAndWait();
    }
}
