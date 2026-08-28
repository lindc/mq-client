package org.bear.mqclient.kafka.ui;

import org.bear.mqclient.core.model.MQConnectionConfig;
import org.bear.mqclient.core.service.MQService;
import org.bear.mqclient.core.spi.MQUIProvider;
import javafx.scene.Node;
import javafx.scene.control.Menu;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.util.List;

/**
 * Kafka UI 提供者 SPI 实现。
 */
public class KafkaUIProvider implements MQUIProvider {

    private final Image iconImage = new Image(getClass().getResourceAsStream("/icons/kafka.png"));
    private KafkaMainPanel mainPanel;

    @Override
    public String getType() { return "KAFKA"; }

    @Override
    public String getDisplayName() { return "Apache Kafka"; }

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
        mainPanel = new KafkaMainPanel(config, service);
        return mainPanel;
    }

    @Override
    public Node createConfigEditor() {
        return new KafkaConfigEditor();
    }

    @Override
    public void applyConfig(Node editor, MQConnectionConfig config) {
        if (editor instanceof KafkaConfigEditor kce) {
            kce.setConfig(config);
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
