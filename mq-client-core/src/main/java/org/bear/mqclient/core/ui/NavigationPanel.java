package org.bear.mqclient.core.ui;

import org.bear.mqclient.core.config.ConnectionStore;
import org.bear.mqclient.core.model.ConnectionState;
import org.bear.mqclient.core.model.MQConnectionConfig;
import org.bear.mqclient.core.spi.MQUIProvider;
import org.bear.mqclient.core.util.I18n;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 左侧连接列表面板（平铺 ListView，不按 MQ 分组）。
 * <p>
 * 每个连接渲染为小卡片：左侧 MQ 图标（由各 MQ 的 {@link MQUIProvider#getIcon()} 提供），
 * 右侧上为连接名、下为连接状态小圆点（灰=未连接 / 黄=连接中 / 绿=已连接 / 红=失败）。
 */
public class NavigationPanel {

    /** 状态小圆点颜色 */
    private static final Map<ConnectionState, String> STATE_COLORS = Map.of(
            ConnectionState.DISCONNECTED, "#b0b4ba",
            ConnectionState.CONNECTING, "#f0a020",
            ConnectionState.CONNECTED, "#2ecc71",
            ConnectionState.FAILED, "#e74c3c"
    );

    private final VBox view;
    private final ListView<MQConnectionConfig> listView;
    private final ObservableList<MQConnectionConfig> items;
    private final Map<String, ConnectionState> states = new ConcurrentHashMap<>();
    private final ConnectionStore store;
    private final Map<String, MQUIProvider> uiProviders;
    private final Consumer<MQConnectionConfig> onSelect;

    public NavigationPanel(ConnectionStore store, Map<String, MQUIProvider> uiProviders,
                           Consumer<MQConnectionConfig> onSelect) {
        this.store = store;
        this.uiProviders = uiProviders;
        this.onSelect = onSelect;
        this.items = FXCollections.observableArrayList();

        this.listView = new ListView<>(items);
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(MQConnectionConfig cfg, boolean empty) {
                super.updateItem(cfg, empty);
                if (empty || cfg == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                setText(null);
                setGraphic(buildCard(cfg));
            }
        });

        // 点击选择
        listView.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null) onSelect.accept(sel);
        });

        // 右键菜单
        listView.setContextMenu(createContextMenu());

        // 布局：无标题；列表内容圆角裁剪（不能裁剪 view 本身，否则溢出阴影被裁掉）
        VBox.setVgrow(listView, Priority.ALWAYS);
        listView.setStyle("-fx-background-color: transparent;");
        Rectangle clip = new Rectangle();
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        clip.widthProperty().bind(listView.widthProperty());
        clip.heightProperty().bind(listView.heightProperty());
        listView.setClip(clip);
        this.view = new VBox(listView);
        view.setStyle("-fx-background-color: #ffffff;"
                + " -fx-background-radius: 12;"
                + " -fx-effect: dropshadow(gaussian, rgba(70, 110, 160, 0.4), 18, 0.2, 0, 5);");
        view.setMinWidth(140);

        refresh();
        store.addChangeListener(this::refresh);
    }

    public Node getView() { return view; }

    /** 更新连接状态（驱动状态圆点），由 MainController 在连接流程中调用；可来自任意线程 */
    public void updateState(String connectionId, ConnectionState state) {
        states.put(connectionId, state);
        javafx.application.Platform.runLater(listView::refresh);
    }

    public void refresh() {
        items.setAll(store.listAll());
        // 清理已删除连接的状态
        states.keySet().removeIf(id -> store.findById(id) == null);
    }

    // ── 卡片渲染 ──

    private Node buildCard(MQConnectionConfig cfg) {
        MQUIProvider provider = uiProviders.get(cfg.getType());
        Node icon = provider != null && provider.getIcon() != null
                ? provider.getIcon()
                : new Region();
        if (icon instanceof Region r) {
            r.setPrefSize(28, 28);
        }

        Label name = new Label(cfg.getName());
        name.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        name.setMaxWidth(Double.MAX_VALUE);

        Region dot = new Region();
        dot.setPrefSize(8, 8);
        dot.setStyle("-fx-background-radius: 4; -fx-background-color: " + stateColor(cfg.getId()));

        VBox right = new VBox(4, name, dot);
        right.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(right, Priority.ALWAYS);

        HBox card = new HBox(10, icon, right);
        card.setPadding(new Insets(6, 8, 6, 8));
        return card;
    }

    private String stateColor(String connectionId) {
        return STATE_COLORS.get(states.getOrDefault(connectionId, ConnectionState.DISCONNECTED));
    }

    // ── 右键菜单 ──

    private ContextMenu createContextMenu() {
        ContextMenu menu = new ContextMenu();

        MenuItem editItem = new MenuItem(I18n.get("navigation.edit"));
        editItem.setOnAction(e -> {
            MQConnectionConfig sel = listView.getSelectionModel().getSelectedItem();
            if (sel != null) {
                // TODO: 打开编辑对话框
            }
        });

        MenuItem deleteItem = new MenuItem(I18n.get("navigation.delete"));
        deleteItem.setOnAction(e -> {
            MQConnectionConfig sel = listView.getSelectionModel().getSelectedItem();
            if (sel != null) {
                store.remove(sel.getId());
            }
        });

        menu.getItems().addAll(editItem, deleteItem);
        return menu;
    }
}
