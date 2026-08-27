package io.mqclient.core.ui;

import io.mqclient.core.config.ConnectionStore;
import io.mqclient.core.config.LocaleHolder;
import io.mqclient.core.model.MQConnectionConfig;
import io.mqclient.core.service.MQService;
import io.mqclient.core.spi.MQServiceProvider;
import io.mqclient.core.spi.MQUIProvider;
import org.springframework.context.MessageSource;
import javafx.scene.Node;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.layout.BorderPane;

import java.util.*;

/**
 * 主窗口控制器。
 * <p>
 * 负责：左侧连接导航 + 右侧内容区切换 + 菜单栏动态注入。
 */
public class MainController {

    private final BorderPane root;
    private final NavigationPanel navigation;
    private final MenuBarManager menuBarManager;
    private final ConnectionStore connectionStore;

    // 当前选中的连接和 UI 提供者
    private MQConnectionConfig currentConfig;
    private MQUIProvider currentUIProvider;
    private MQService currentService;
    private Node currentPanel;

    // 通过 ServiceLoader 发现的所有 Provider
    private final Map<String, MQServiceProvider> serviceProviders;
    private final Map<String, MQUIProvider> uiProviders;

    public MainController(ConnectionStore connectionStore,
                          MessageSource messageSource, LocaleHolder localeHolder) {
        this.connectionStore = connectionStore;
        this.serviceProviders = new HashMap<>();
        this.uiProviders = new HashMap<>();

        // 扫描 SPI
        discoverProviders();

        // 菜单栏
        MenuBar menuBar = new MenuBar();
        this.menuBarManager = new MenuBarManager(menuBar, this::onNewConnection,
                messageSource, localeHolder);

        // 左侧导航
        this.navigation = new NavigationPanel(connectionStore, this::onConnectionSelected);

        // 根布局
        this.root = new BorderPane();
        this.root.setTop(menuBar);
        this.root.setLeft(navigation.getView());
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

        // 获取对应的 Provider
        MQServiceProvider svcProvider = serviceProviders.get(config.getType());
        MQUIProvider uiProvider = uiProviders.get(config.getType());

        if (svcProvider == null || uiProvider == null) {
            System.err.println("[Main] No provider found for type: " + config.getType());
            return;
        }

        currentUIProvider = uiProvider;

        // 建立连接
        MQService service = svcProvider.createService();
        currentService = service;
        service.connect(config).whenComplete((v, ex) -> {
            if (ex != null) {
                System.err.println("[Main] Connection failed: " + ex.getMessage());
                return;
            }
            javafx.application.Platform.runLater(() -> {
                // 创建 MQ 专属面板
                currentPanel = uiProvider.createMainPanel(config, service);
                root.setCenter(currentPanel);
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
        if (currentUIProvider != null) {
            currentUIProvider.onPanelClosed();
            currentUIProvider = null;
        }
        if (currentPanel != null) {
            root.setCenter(null);
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
