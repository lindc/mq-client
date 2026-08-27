package io.mqclient.rabbitmq.ui;

import io.mqclient.core.model.MQConnectionConfig;
import io.mqclient.core.service.MQService;
import io.mqclient.core.spi.MQUIProvider;
import javafx.scene.Node;
import javafx.scene.control.Menu;

import java.util.List;

/**
 * RabbitMQ UI 提供者 SPI 实现。
 */
public class RabbitMQUIProvider implements MQUIProvider {

    private RabbitMQMainPanel mainPanel;

    @Override
    public String getType() { return "RABBITMQ"; }

    @Override
    public String getDisplayName() { return "RabbitMQ"; }

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
