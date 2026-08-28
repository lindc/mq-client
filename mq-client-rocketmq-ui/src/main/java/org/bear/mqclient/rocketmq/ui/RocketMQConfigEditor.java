package org.bear.mqclient.rocketmq.ui;

import org.bear.mqclient.core.model.MQConnectionConfig;
import org.bear.mqclient.core.util.I18n;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/**
 * RocketMQ 连接配置编辑器。
 */
public class RocketMQConfigEditor extends VBox {

    private final TextField nameField;
    private final TextField nsAddrField;
    private final TextField consumerGroupField;
    private final TextField producerGroupField;

    private MQConnectionConfig config;

    public RocketMQConfigEditor() {
        setPadding(new Insets(16));
        setSpacing(8);
        setPrefWidth(450);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);

        nameField          = new TextField();
        nsAddrField        = new TextField("localhost:9876");
        consumerGroupField = new TextField("mq-client-consumer");
        producerGroupField = new TextField("mq-client-producer");

        nameField.textProperty().addListener((o, a, b) -> { if (config != null) config.setName(b); });
        nsAddrField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("namesrvAddr", b); });
        consumerGroupField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("consumerGroup", b); });
        producerGroupField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("producerGroup", b); });

        grid.addRow(0, new Label(I18n.get("common.name")),           nameField);
        grid.addRow(1, new Label("NameSrv Addr:"),   nsAddrField);
        grid.addRow(2, new Label("Consumer Group:"), consumerGroupField);
        grid.addRow(3, new Label("Producer Group:"), producerGroupField);

        Button testBtn = new Button(I18n.get("common.testConnection"));
        testBtn.setOnAction(e -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                    I18n.get("rocketmq.testConnect", nsAddrField.getText()));
            alert.showAndWait();
        });

        getChildren().addAll(grid, testBtn);
    }

    public void setConfig(MQConnectionConfig cfg) {
        this.config = cfg;
        nameField.setText(cfg.getName());
        nsAddrField.setText(cfg.getProperty("namesrvAddr"));
        consumerGroupField.setText(cfg.getProperty("consumerGroup"));
        producerGroupField.setText(cfg.getProperty("producerGroup"));
    }

    private MQConnectionConfig cfg() { return config; }
}
