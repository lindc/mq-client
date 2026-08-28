package org.bear.mqclient.artemis.ui;

import org.bear.mqclient.core.model.MQConnectionConfig;
import org.bear.mqclient.core.ui.FormLabels;
import org.bear.mqclient.core.util.I18n;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/**
 * Artemis 连接配置编辑器。
 */
public class ArtemisConfigEditor extends VBox {

    private final TextField nameField;
    private final TextField hostField;
    private final TextField portField;
    private final TextField usernameField;
    private final PasswordField passwordField;

    private MQConnectionConfig config;

    public ArtemisConfigEditor() {
        setPadding(new Insets(16));
        setSpacing(8);
        setPrefWidth(400);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);

        nameField     = new TextField();
        hostField     = new TextField("localhost");
        portField     = new TextField("61616");
        usernameField = new TextField("artemis");
        passwordField = new PasswordField();
        passwordField.setText("artemis");

        nameField.textProperty().addListener((o, a, b) -> { if (config != null) config.setName(b); });
        hostField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("host", b); });
        portField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("port", b); });
        usernameField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("username", b); });
        passwordField.textProperty().addListener((o, a, b) -> { if (config != null) cfg().setProperty("password", b); });

        grid.addRow(0, FormLabels.label(I18n.get("common.name")),   nameField);
        grid.addRow(1, FormLabels.label(I18n.get("common.host")),     hostField);
        grid.addRow(2, FormLabels.label(I18n.get("common.port")),     portField);
        grid.addRow(3, FormLabels.label(I18n.get("common.username")), usernameField);
        grid.addRow(4, FormLabels.label(I18n.get("common.password")), passwordField);

        Button testBtn = new Button(I18n.get("common.testConnection"));
        testBtn.setOnAction(e -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                    I18n.get("common.testingConnect", hostField.getText(), portField.getText()));
            alert.showAndWait();
        });

        getChildren().addAll(grid, testBtn);
    }

    public void setConfig(MQConnectionConfig cfg) {
        this.config = cfg;
        nameField.setText(cfg.getName());
        hostField.setText(cfg.getProperty("host"));
        String port = cfg.getProperty("port");
        portField.setText(port != null ? port : "61616");
        usernameField.setText(cfg.getProperty("username"));
        passwordField.setText(cfg.getProperty("password"));
    }

    private MQConnectionConfig cfg() { return config; }
}
