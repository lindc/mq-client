package org.bear.mqclient.ibm.ui;

import org.bear.mqclient.core.model.MQConnectionConfig;
import org.bear.mqclient.core.util.I18n;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/**
 * IBM MQ 连接配置编辑器。
 * <p>
 * 字段：连接名 / 队列管理器 / 主机 / 端口 / 通道 / SSL CipherSuite / 用户名 / 密码
 */
public class IBMMQConfigEditor extends VBox {

    private final TextField nameField;
    private final TextField qmgrField;
    private final TextField hostField;
    private final TextField portField;
    private final TextField channelField;
    private final TextField sslField;
    private final TextField usernameField;
    private final PasswordField passwordField;

    private MQConnectionConfig config;

    public IBMMQConfigEditor() {
        setPadding(new Insets(16));
        setSpacing(8);
        setPrefWidth(450);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);

        nameField    = new TextField();
        qmgrField    = new TextField();
        hostField    = new TextField("localhost");
        portField    = new TextField("1414");
        channelField = new TextField("SYSTEM.DEF.SVRCONN");
        sslField     = new TextField();
        usernameField = new TextField();
        passwordField = new PasswordField();

        grid.addRow(0, new Label(I18n.get("common.name")),          nameField);
        grid.addRow(1, new Label(I18n.get("ibm.queueManager")),       qmgrField);
        grid.addRow(2, new Label(I18n.get("common.host")),            hostField);
        grid.addRow(3, new Label(I18n.get("common.port")),            portField);
        grid.addRow(4, new Label(I18n.get("ibm.channel")),            channelField);
        grid.addRow(5, new Label("SSL CipherSuite:"), sslField);
        grid.addRow(6, new Label(I18n.get("common.username")),          usernameField);
        grid.addRow(7, new Label(I18n.get("common.password")),            passwordField);

        // 字段变化时自动同步到 config
        nameField.textProperty().addListener((o, a, b) -> { if (config != null) config.setName(b); });
        qmgrField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("queueManager", b); });
        hostField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("host", b); });
        portField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("port", b); });
        channelField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("channel", b); });
        sslField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("sslCipherSuite", b); });
        usernameField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("username", b); });
        passwordField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("password", b); });

        Button testBtn = new Button(I18n.get("common.testConnection"));
        testBtn.setOnAction(e -> testConnection());

        getChildren().addAll(grid, testBtn);
    }

    public void setConfig(MQConnectionConfig cfg) {
        this.config = cfg;
        nameField.setText(cfg.getName());
        qmgrField.setText(cfg.getProperty("queueManager"));
        hostField.setText(cfg.getProperty("host"));
        String port = cfg.getProperty("port");
        portField.setText(port != null ? port : "1414");
        channelField.setText(cfg.getProperty("channel"));
        sslField.setText(cfg.getProperty("sslCipherSuite"));
        usernameField.setText(cfg.getProperty("username"));
        passwordField.setText(cfg.getProperty("password"));
    }

    private MQConnectionConfig cfg() { return config; }

    private void testConnection() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION,
                I18n.get("ibm.testNotImplemented", hostField.getText(), portField.getText(), qmgrField.getText()));
        alert.showAndWait();
    }
}
