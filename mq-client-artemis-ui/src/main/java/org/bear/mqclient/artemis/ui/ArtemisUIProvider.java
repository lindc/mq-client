package org.bear.mqclient.artemis.ui;

import org.bear.mqclient.core.model.MQConnectionConfig;
import org.bear.mqclient.core.service.MQService;
import org.bear.mqclient.core.spi.MQUIProvider;
import javafx.scene.Node;
import javafx.scene.control.Menu;

import java.util.List;

/**
 * Artemis UI 提供者 SPI 实现。
 */
public class ArtemisUIProvider implements MQUIProvider {

    private ArtemisMainPanel mainPanel;

    @Override
    public String getType() { return "ARTEMIS"; }

    @Override
    public String getDisplayName() { return "ActiveMQ Artemis"; }

    @Override
    public Node createMainPanel(MQConnectionConfig config, MQService service) {
        mainPanel = new ArtemisMainPanel(config, service);
        return mainPanel;
    }

    @Override
    public Node createConfigEditor() {
        return new ArtemisConfigEditor();
    }

    @Override
    public void applyConfig(Node editor, MQConnectionConfig config) {
        if (editor instanceof ArtemisConfigEditor ace) {
            ace.setConfig(config);
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
