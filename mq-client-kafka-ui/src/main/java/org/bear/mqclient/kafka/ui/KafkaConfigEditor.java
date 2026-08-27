package org.bear.mqclient.kafka.ui;

import org.bear.mqclient.core.model.MQConnectionConfig;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/**
 * Kafka 连接配置编辑器。
 * <p>
 * 字段：连接名 / Bootstrap Servers / Group ID / Security Protocol / SASL
 */
public class KafkaConfigEditor extends VBox {

    private final TextField nameField;
    private final TextField bootstrapField;
    private final TextField groupIdField;
    private final ComboBox<String> securityCombo;
    private final TextField saslMechField;
    private final TextField saslJaasField;

    private MQConnectionConfig config;

    public KafkaConfigEditor() {
        setPadding(new Insets(16));
        setSpacing(8);
        setPrefWidth(450);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);

        nameField       = new TextField();
        bootstrapField  = new TextField("localhost:9092");
        groupIdField    = new TextField("mq-client");
        securityCombo   = new ComboBox<>();
        securityCombo.getItems().addAll("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL");
        securityCombo.setValue("PLAINTEXT");
        saslMechField   = new TextField();
        saslJaasField   = new TextField();

        grid.addRow(0, new Label("连接名:"),             nameField);
        grid.addRow(1, new Label("Bootstrap Servers:"), bootstrapField);
        grid.addRow(2, new Label("Consumer Group ID:"), groupIdField);
        grid.addRow(3, new Label("Security Protocol:"), securityCombo);
        grid.addRow(4, new Label("SASL Mechanism:"),    saslMechField);
        grid.addRow(5, new Label("SASL JAAS Config:"),  saslJaasField);

        // 字段同步
        nameField.textProperty().addListener((o, a, b) -> { if (config != null) config.setName(b); });
        bootstrapField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("bootstrapServers", b); });
        groupIdField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("groupId", b); });
        securityCombo.valueProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("securityProtocol", b); });
        saslMechField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("saslMechanism", b); });
        saslJaasField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("saslJaasConfig", b); });

        Button testBtn = new Button("测试连接");
        testBtn.setOnAction(e -> testConnection());

        getChildren().addAll(grid, testBtn);
    }

    public void setConfig(MQConnectionConfig cfg) {
        this.config = cfg;
        nameField.setText(cfg.getName());
        bootstrapField.setText(cfg.getProperty("bootstrapServers"));
        groupIdField.setText(cfg.getProperty("groupId"));
        String sec = cfg.getProperty("securityProtocol");
        if (sec != null) securityCombo.setValue(sec);
        saslMechField.setText(cfg.getProperty("saslMechanism"));
        saslJaasField.setText(cfg.getProperty("saslJaasConfig"));
    }

    private MQConnectionConfig cfg() { return config; }

    private void testConnection() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION,
                "测试连接功能。\n将尝试连接到 " + bootstrapField.getText());
        alert.showAndWait();
    }
}
