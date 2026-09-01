package org.bear.mqclient.artemis.ui;

import org.bear.mqclient.core.model.MQConnectionConfig;
import org.bear.mqclient.core.service.MQService;
import org.bear.mqclient.core.spi.MQUIProvider;
import org.bear.mqclient.core.spi.MQNavPage;
import javafx.scene.Node;
import javafx.scene.control.Menu;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.util.List;

/**
 * Artemis UI 提供者 SPI 实现。
 */
public class ArtemisUIProvider implements MQUIProvider {

    private final Image iconImage = new Image(getClass().getResourceAsStream("/icons/artemis.png"));
    private ArtemisMainPanel mainPanel;

    @Override
    public String getType() { return "ARTEMIS"; }

    @Override
    public String getDisplayName() { return "ActiveMQ Artemis"; }

    @Override
    public Node getIcon() {
        ImageView icon = new ImageView(iconImage);
        icon.setFitWidth(28);
        icon.setFitHeight(28);
        icon.setPreserveRatio(true);
        return icon;
    }

    @Override
    public List<MQNavPage> getNavPages() {
        return List.of(
                new MQNavPage("队列", "queues"),
                new MQNavPage("地址", "addresses"),
                new MQNavPage("连接", "connections")
        );
    }

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
    public Runnable getTestAction(Node editor) {
        return editor instanceof ArtemisConfigEditor ace ? ace::testConnection : null;
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
