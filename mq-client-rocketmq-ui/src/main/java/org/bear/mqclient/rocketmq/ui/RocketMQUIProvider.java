package org.bear.mqclient.rocketmq.ui;

import org.bear.mqclient.core.model.MQConnectionConfig;
import org.bear.mqclient.core.service.MQService;
import org.bear.mqclient.core.spi.MQUIProvider;
import javafx.scene.Node;
import javafx.scene.control.Menu;

import java.util.List;

/**
 * RocketMQ UI 提供者 SPI 实现。
 */
public class RocketMQUIProvider implements MQUIProvider {

    private RocketMQMainPanel mainPanel;

    @Override
    public String getType() { return "ROCKETMQ"; }

    @Override
    public String getDisplayName() { return "Apache RocketMQ"; }

    @Override
    public Node createMainPanel(MQConnectionConfig config, MQService service) {
        mainPanel = new RocketMQMainPanel(config, service);
        return mainPanel;
    }

    @Override
    public Node createConfigEditor() {
        return new RocketMQConfigEditor();
    }

    @Override
    public void applyConfig(Node editor, MQConnectionConfig config) {
        if (editor instanceof RocketMQConfigEditor rce) {
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
