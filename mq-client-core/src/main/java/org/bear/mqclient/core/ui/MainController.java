package org.bear.mqclient.core.ui;

import lombok.extern.slf4j.Slf4j;
import org.bear.mqclient.core.config.ConnectionStore;
import org.bear.mqclient.core.config.LocaleHolder;
import org.bear.mqclient.core.model.ConnectionState;
import org.bear.mqclient.core.model.MQConnectionConfig;
import org.bear.mqclient.core.service.MQService;
import org.bear.mqclient.core.spi.MQServiceProvider;
import org.bear.mqclient.core.spi.MQUIProvider;
import javafx.scene.Node;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.SplitPane;
import javafx.geometry.Insets;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;

import java.util.*;

/**
 * 主窗口控制器。
 * <p>
 * 负责：左侧连接导航 + 右侧内容区切换 + 菜单栏动态注入。
 */
@Slf4j
public class MainController {

    private final BorderPane root;
    private final NavigationPanel navigation;
    private final MenuBarManager menuBarManager;
    private final ConnectionStore connectionStore;
    private final StackPane contentArea;

    // 当前选中的连接和 UI 提供者
    private MQConnectionConfig currentConfig;
    private MQUIProvider currentUIProvider;
    private MQService currentService;
    private Node currentPanel;

    // 通过 ServiceLoader 发现的所有 Provider
    private final Map<String, MQServiceProvider> serviceProviders;
    private final Map<String, MQUIProvider> uiProviders;

    public MainController(ConnectionStore connectionStore, LocaleHolder localeHolder) {
        this.connectionStore = connectionStore;
        this.serviceProviders = new HashMap<>();
        this.uiProviders = new HashMap<>();

        // 扫描 SPI
        discoverProviders();

        // 菜单栏
        MenuBar menuBar = new MenuBar();
        this.menuBarManager = new MenuBarManager(menuBar, this::onNewConnection, localeHolder);

        // 左侧导航
        this.navigation = new NavigationPanel(connectionStore, uiProviders, this::onConnectionSelected);

        // 根布局：菜单栏 +（导航 | 内容区）可拖动的 SplitPane
        this.contentArea = new StackPane();
        // 背景 + 边框放在内层 contentArea 上：外层 rightPanel 只留空隙（透明，露出
        // SplitPane 底色），上下各 6px 空隙，与左侧悬浮卡片的结构对称
        contentArea.setPadding(new Insets(4));
        contentArea.setStyle("-fx-background-color: #eef0f3;"
                + " -fx-border-color: #c9ced6; -fx-border-width: 1;");
        // 导航面板外包一层：留 6px 空隙让阴影完整可见；空隙填背景灰（与 SplitPane 同色），
        // 白色面板在灰背景上靠色差 + 阴影悬浮
        StackPane navWrap = new StackPane(navigation.getView());
        navWrap.setPadding(new Insets(6));
        navWrap.setStyle("-fx-background-color: #e8eaed; -fx-background-radius: 12;");

        // 右侧内容区外层：仅留空隙，不做背景（背景在 contentArea 上，见上）；
        // 左侧距收窄到 2px，压缩中间空隙（上下右保持 6px，窗口边缘空隙不变）
        StackPane rightPanel = new StackPane(contentArea);
        rightPanel.setPadding(new Insets(6, 6, 6, 2));

        SplitPane splitPane = new SplitPane(navWrap, rightPanel);
        splitPane.setDividerPositions(0.2);
        // 分隔条样式（透明 + hover 浮现）在 css/main.css 中定义；setStyle 只能写声明，
        // 不能写选择器规则（否则解析失败且样式不生效）
        splitPane.getStyleClass().add("mq-split-pane");
        splitPane.setStyle("-fx-background-color: #e8eaed;");

        this.root = new BorderPane();
        this.root.setTop(menuBar);
        this.root.setCenter(splitPane);
    }

    // ── SPI 发现 ──

    private void discoverProviders() {
        ServiceLoader.load(MQServiceProvider.class)
                .forEach(p -> serviceProviders.put(p.getType(), p));
        ServiceLoader.load(MQUIProvider.class)
                .forEach(p -> uiProviders.put(p.getType(), p));
    }

    // ── 连接选择 ──

    private void onConnectionSelected(MQConnectionConfig config) {
        // 清理旧连接
        closeCurrent();

        if (config == null) return;

        currentConfig = config;
        navigation.updateState(config.getId(), ConnectionState.CONNECTING);

        // 获取对应的 Provider
        MQServiceProvider svcProvider = serviceProviders.get(config.getType());
        MQUIProvider uiProvider = uiProviders.get(config.getType());

        if (svcProvider == null || uiProvider == null) {
            log.warn("No provider found for type: {}", config.getType());
            return;
        }

        currentUIProvider = uiProvider;

        // 建立连接
        MQService service = svcProvider.createService();
        currentService = service;
        service.connect(config).whenComplete((v, ex) -> {
            if (ex != null) {
                navigation.updateState(config.getId(), ConnectionState.FAILED);
                log.error("Connection failed: {}", ex.getMessage());
                return;
            }
            navigation.updateState(config.getId(), ConnectionState.CONNECTED);
            javafx.application.Platform.runLater(() -> {
                // 创建 MQ 专属面板
                currentPanel = uiProvider.createMainPanel(config, service);
                contentArea.getChildren().setAll(currentPanel);
                // 注入菜单
                menuBarManager.injectMenus(uiProvider.getMenus());
            });
        });
    }

    private void closeCurrent() {
        if (currentService != null) {
            currentService.disconnect();
            currentService = null;
        }
        if (currentConfig != null) {
            navigation.updateState(currentConfig.getId(), ConnectionState.DISCONNECTED);
        }
        if (currentUIProvider != null) {
            currentUIProvider.onPanelClosed();
            currentUIProvider = null;
        }
        if (currentPanel != null) {
            contentArea.getChildren().clear();
            currentPanel = null;
        }
        menuBarManager.clearInjectedMenus();
        currentConfig = null;
    }

    // ── 新建连接 ──

    private void onNewConnection() {
        NewConnectionDialog dialog = new NewConnectionDialog(uiProviders);
        Optional<MQConnectionConfig> result = dialog.showAndWait();
        result.ifPresent(config -> {
            connectionStore.add(config);
            navigation.refresh();
        });
    }

    // ── 公开 ──

    public BorderPane getRoot() { return root; }
}
