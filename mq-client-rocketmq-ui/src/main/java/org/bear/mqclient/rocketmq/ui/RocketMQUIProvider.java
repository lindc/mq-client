package org.bear.mqclient.rocketmq.ui;

import org.bear.mqclient.core.model.MQConnectionConfig;
import org.bear.mqclient.core.service.MQService;
import org.bear.mqclient.core.spi.MQUIProvider;
import javafx.scene.Node;
import javafx.scene.control.Menu;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.util.List;

/**
 * RocketMQ UI 提供者 SPI 实现。
 */
public class RocketMQUIProvider implements MQUIProvider {

    private final Image iconImage = new Image(getClass().getResourceAsStream("/icons/rocketmq.png"));
    private RocketMQMainPanel mainPanel;

    @Override
    public String getType() { return "ROCKETMQ"; }

    @Override
    public String getDisplayName() { return "Apache RocketMQ"; }

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
    public Runnable getTestAction(Node editor) {
        return editor instanceof RocketMQConfigEditor rce ? rce::testConnection : null;
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
