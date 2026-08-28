package org.bear.mqclient.rabbitmq.ui;

import org.bear.mqclient.core.model.MQConnectionConfig;
import org.bear.mqclient.core.service.MQService;
import org.bear.mqclient.core.spi.MQUIProvider;
import javafx.scene.Node;
import javafx.scene.control.Menu;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.util.List;

/**
 * RabbitMQ UI 提供者 SPI 实现。
 */
public class RabbitMQUIProvider implements MQUIProvider {

    private final Image iconImage = new Image(getClass().getResourceAsStream("/icons/rabbitmq.png"));
    private RabbitMQMainPanel mainPanel;

    @Override
    public String getType() { return "RABBITMQ"; }

    @Override
    public String getDisplayName() { return "RabbitMQ"; }

    @Override
    public Node getIcon() {
        ImageView icon = new ImageView(iconImage);
        icon.setFitWidth(28);
        icon.setFitHeight(28);
        icon.setPreserveRatio(true);
        return icon;
    }

    @Override
    public Node createMainPanel(MQConnectionConfig config, MQService service) {
        mainPanel = new RabbitMQMainPanel(config, service);
        return mainPanel;
    }

    @Override
    public Node createConfigEditor() {
        return new RabbitMQConfigEditor();
    }

    @Override
    public void applyConfig(Node editor, MQConnectionConfig config) {
        if (editor instanceof RabbitMQConfigEditor rce) {
            rce.setConfig(config);
        }
    }

    @Override
    public List<Menu> getMenus() {
        return mainPanel != null ? mainPanel.getMenus() : List.of();
    }

    @Override
    public void onPanelClosed() {
        if (mainPanel != null) {
            mainPanel.cleanup();
            mainPanel = null;
        }
    }
}
