package io.mqclient.ibm.ui;

import io.mqclient.core.model.MQConnectionConfig;
import io.mqclient.core.service.MQService;
import io.mqclient.core.spi.MQUIProvider;
import javafx.scene.Node;
import javafx.scene.control.Menu;

import java.util.List;

/**
 * IBM MQ UI 提供者 SPI 实现。
 */
public class IBMMQUIProvider implements MQUIProvider {

    private IBMMQMainPanel mainPanel;

    @Override
    public String getType() { return "IBM_MQ"; }

    @Override
    public String getDisplayName() { return "IBM MQ"; }

    @Override
    public Node createMainPanel(MQConnectionConfig config, MQService service) {
        mainPanel = new IBMMQMainPanel(config, service);
        return mainPanel;
    }

    @Override
    public Node createConfigEditor() {
        return new IBMMQConfigEditor();
    }

    @Override
    public void applyConfig(Node editor, MQConnectionConfig config) {
        if (editor instanceof IBMMQConfigEditor ice) {
            ice.setConfig(config);
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
