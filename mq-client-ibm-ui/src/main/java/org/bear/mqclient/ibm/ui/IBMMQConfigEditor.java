package org.bear.mqclient.ibm.ui;

import org.bear.mqclient.core.model.MQConnectionConfig;
import org.bear.mqclient.core.ui.FormLabels;
import org.bear.mqclient.core.util.I18n;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;

/**
 * IBM MQ 连接配置编辑器。
 * <p>
 * 字段：连接名 / 队列管理器 / 连接模式（Client 或 Bind） / 主机 / 端口 / 通道 /
 * SSL CipherSuite / 用户名 / 密码
 */
public class IBMMQConfigEditor extends VBox {

    private final TextField nameField;
    private final RadioButton clientMode;
    private final RadioButton bindMode;
    private final TextField qmgrField;
    private final TextField hostField;
    private final TextField portField;
    private final TextField channelField;
    private final ComboBox<String> sslCombo;
    private final TextField sslTrustStoreField;
    private final PasswordField sslTrustStorePwdField;
    private final TextField sslKeyStoreField;
    private final PasswordField sslKeyStorePwdField;
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
        // label 列固定宽，输入列占满剩余空间（与各 MQ 编辑器统一后输入框等长）
        ColumnConstraints labelCol = new ColumnConstraints(FormLabels.WIDTH);
        ColumnConstraints inputCol = new ColumnConstraints();
        inputCol.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelCol, inputCol);

        nameField    = new TextField();
        qmgrField    = new TextField();
        hostField    = new TextField("localhost");
        portField    = new TextField("1414");
        channelField = new TextField("SYSTEM.DEF.SVRCONN");
        // SSL CipherSuite：下拉选择（首项为空 = 不使用 SSL），可编辑以兼容自定义/旧值
        sslCombo = new ComboBox<>();
        sslCombo.getItems().addAll("",
                "TLS_AES_128_GCM_SHA256",
                "TLS_AES_256_GCM_SHA384",
                "TLS_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
                "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384");
        sslCombo.setValue("");
        sslCombo.setEditable(true);
        sslCombo.setMaxWidth(Double.MAX_VALUE); // 与同列输入框等宽拉伸
        // 证书库按需填写：不填则走 JVM 默认信任库（公共 CA 或已导入 cacerts 的证书）
        sslTrustStoreField = new TextField();
        sslKeyStoreField = new TextField();
        sslTrustStorePwdField = new PasswordField();
        sslKeyStorePwdField = new PasswordField();
        // 路径用文件选择框选，不用手写
        HBox trustStoreBox = filePicker(sslTrustStoreField);
        HBox keyStoreBox = filePicker(sslKeyStoreField);
        usernameField = new TextField();
        passwordField = new PasswordField();

        // 连接模式：Client（远程，需 host/port/channel）或 Bind（本地绑定，只需队列管理器名）
        ToggleGroup modeGroup = new ToggleGroup();
        clientMode = new RadioButton(I18n.get("ibm.mode.client"));
        bindMode = new RadioButton(I18n.get("ibm.mode.bind"));
        clientMode.setToggleGroup(modeGroup);
        bindMode.setToggleGroup(modeGroup);
        clientMode.setSelected(true);
        HBox modeBox = new HBox(16, clientMode, bindMode);

        grid.addRow(0, FormLabels.label(I18n.get("common.name")),          nameField);
        grid.addRow(1, FormLabels.label(I18n.get("ibm.queueManager")),       qmgrField);
        grid.addRow(2, FormLabels.label(I18n.get("ibm.connectionMode")),     modeBox);
        grid.addRow(3, FormLabels.label(I18n.get("common.host")),            hostField);
        grid.addRow(4, FormLabels.label(I18n.get("common.port")),            portField);
        grid.addRow(5, FormLabels.label(I18n.get("ibm.channel")),            channelField);
        grid.addRow(6, FormLabels.label("SSL CipherSuite:"), sslCombo);
        grid.addRow(7, FormLabels.label(I18n.get("ibm.sslTrustStore")), trustStoreBox);
        grid.addRow(8, FormLabels.label(I18n.get("ibm.sslTrustStorePassword")), sslTrustStorePwdField);
        grid.addRow(9, FormLabels.label(I18n.get("ibm.sslKeyStore")), keyStoreBox);
        grid.addRow(10, FormLabels.label(I18n.get("ibm.sslKeyStorePassword")), sslKeyStorePwdField);
        grid.addRow(11, FormLabels.label(I18n.get("common.username")),          usernameField);
        grid.addRow(12, FormLabels.label(I18n.get("common.password")),            passwordField);

        // 字段变化时自动同步到 config
        nameField.textProperty().addListener((o, a, b) -> { if (config != null) config.setName(b); });
        qmgrField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("queueManager", b); });
        hostField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("host", b); });
        portField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("port", b); });
        channelField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("channel", b); });
        sslCombo.valueProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("sslCipherSuite", b); });
        sslTrustStoreField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("sslTrustStore", b); });
        sslTrustStorePwdField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("sslTrustStorePassword", b); });
        sslKeyStoreField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("sslKeyStore", b); });
        sslKeyStorePwdField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("sslKeyStorePassword", b); });
        usernameField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("username", b); });
        passwordField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("password", b); });

        // 模式切换：Bind 只连本地队列管理器，网络相关字段全部禁用
        modeGroup.selectedToggleProperty().addListener((o, a, b) -> {
            boolean bind = b == bindMode;
            hostField.setDisable(bind);
            portField.setDisable(bind);
            channelField.setDisable(bind);
            sslCombo.setDisable(bind);
            trustStoreBox.setDisable(bind);
            sslTrustStorePwdField.setDisable(bind);
            keyStoreBox.setDisable(bind);
            sslKeyStorePwdField.setDisable(bind);
            if (config != null) cfg().setProperty("connectionMode", bind ? "bind" : "client");
        });

        getChildren().add(grid);
    }

    public void setConfig(MQConnectionConfig cfg) {
        this.config = cfg;
        nameField.setText(cfg.getName());
        // 恢复连接模式（缺省 client，保持与旧配置兼容）
        String mode = cfg.getProperty("connectionMode");
        if ("bind".equalsIgnoreCase(mode)) {
            bindMode.setSelected(true);
        } else {
            clientMode.setSelected(true);
        }
        qmgrField.setText(cfg.getProperty("queueManager"));
        hostField.setText(cfg.getProperty("host"));
        String port = cfg.getProperty("port");
        portField.setText(port != null ? port : "1414");
        channelField.setText(cfg.getProperty("channel"));
        // 已有连接可能存了列表外的值：editable 模式下 setValue 可显示任意文本；空/无则回到空项
        String ssl = cfg.getProperty("sslCipherSuite");
        sslCombo.setValue(ssl != null && !ssl.isEmpty() ? ssl : "");
        sslTrustStoreField.setText(cfg.getProperty("sslTrustStore"));
        sslTrustStorePwdField.setText(cfg.getProperty("sslTrustStorePassword"));
        sslKeyStoreField.setText(cfg.getProperty("sslKeyStore"));
        sslKeyStorePwdField.setText(cfg.getProperty("sslKeyStorePassword"));
        usernameField.setText(cfg.getProperty("username"));
        passwordField.setText(cfg.getProperty("password"));
        // 字段当前值（含构造时的默认值）同步回 config：未编辑过的字段也要进配置，
        // 否则连接卡片/后续读取拿不到 host/port 等默认值
        cfg.setProperty("queueManager", qmgrField.getText());
        cfg.setProperty("connectionMode", bindMode.isSelected() ? "bind" : "client");
        cfg.setProperty("host", hostField.getText());
        cfg.setProperty("port", portField.getText());
        cfg.setProperty("channel", channelField.getText());
        cfg.setProperty("sslCipherSuite", sslCombo.getValue());
        cfg.setProperty("sslTrustStore", sslTrustStoreField.getText());
        cfg.setProperty("sslTrustStorePassword", sslTrustStorePwdField.getText());
        cfg.setProperty("sslKeyStore", sslKeyStoreField.getText());
        cfg.setProperty("sslKeyStorePassword", sslKeyStorePwdField.getText());
        cfg.setProperty("username", usernameField.getText());
        cfg.setProperty("password", passwordField.getText());
    }

    private MQConnectionConfig cfg() { return config; }

    /** 文件选择框组合：文本框 + 浏览按钮，选中的路径写入文本框 */
    private HBox filePicker(TextField field) {
        HBox box = new HBox(4, field);
        HBox.setHgrow(field, Priority.ALWAYS);
        Button browseBtn = new Button(I18n.get("ibm.browse"));
        browseBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.get("ibm.browse"));
            File file = chooser.showOpenDialog(getScene() != null ? getScene().getWindow() : null);
            if (file != null) field.setText(file.getAbsolutePath());
        });
        box.getChildren().add(browseBtn);
        return box;
    }

    public void testConnection() {
        String info = bindMode.isSelected()
                ? I18n.get("ibm.testNotImplementedBind", qmgrField.getText())
                : I18n.get("ibm.testNotImplemented",
                        hostField.getText(), portField.getText(), qmgrField.getText());
        Alert alert = new Alert(Alert.AlertType.INFORMATION, info);
        alert.showAndWait();
    }
}
