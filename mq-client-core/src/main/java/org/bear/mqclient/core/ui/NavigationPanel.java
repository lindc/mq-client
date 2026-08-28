package org.bear.mqclient.core.ui;

import org.bear.mqclient.core.config.ConnectionStore;
import org.bear.mqclient.core.model.ConnectionState;
import org.bear.mqclient.core.model.MQConnectionConfig;
import org.bear.mqclient.core.spi.MQUIProvider;
import org.bear.mqclient.core.util.I18n;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
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
        listView.getStyleClass().add("nav-list"); // 行级卡片样式见 css/main.css
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

        // 布局：无标题文字；面板带背景色 + 小圆角 + 阴影（与行级卡片圆角 8px 一致），
        // 呈悬浮卡片效果（阴影需配合 navWrap 空隙与同色背景才能完整可见）
        VBox.setVgrow(listView, Priority.ALWAYS);
        // ListView 背景透明，露出面板背景
        listView.setStyle("-fx-background-color: transparent;");
        // ListView 内容圆角裁剪：避免直角内容把阴影形状顶出尖角。
        // 注意：不能裁剪 view 本身——clip 作用于节点全部渲染，会把溢出面板边界的阴影一并裁掉
        Rectangle clip = new Rectangle();
        clip.setArcWidth(16);
        clip.setArcHeight(16);
        clip.widthProperty().bind(listView.widthProperty());
        clip.heightProperty().bind(listView.heightProperty());
        listView.setClip(clip);
        this.view = new VBox(listView);
        // 面板白色、背景灰（#e8eaed）：色差 + 阴影形成悬浮卡片；阴影只向下偏移避免右缘灰带
        view.setStyle("-fx-background-color: #ffffff;"
                + " -fx-background-radius: 8;"
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
        // 统一图标显示尺寸为 40px（provider 返回的 ImageView 按此缩放，Region 占位同尺寸）
        if (icon instanceof ImageView iv) {
            iv.setFitWidth(40);
            iv.setFitHeight(40);
        } else if (icon instanceof Region r) {
            r.setPrefSize(40, 40);
        }
        // 图标包一层 StackPane：随行高拉伸、图标居中，上下空隙均匀（直接放 HBox
        // 会被 fillHeight 拉伸或出现视觉偏移）
        StackPane iconBox = new StackPane(icon);
        iconBox.setPrefSize(40, 40);
        HBox.setHgrow(iconBox, Priority.NEVER);

        Label name = new Label(cfg.getName());
        name.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        name.setMaxWidth(Double.MAX_VALUE);

        Region dot = new Region();
        dot.setPrefSize(8, 8);
        dot.setMaxSize(8, 8); // 锁死尺寸，否则会被 VBox fillWidth 拉成长条
        dot.setTranslateY(1); // 文字含 descent，几何中心略偏下，微调 1px 视觉对齐
        dot.setStyle("-fx-background-radius: 4; -fx-background-color: " + stateColor(cfg.getId()));

        VBox right;
        String address = addressOf(cfg);
        if (!address.isEmpty()) {
            Label addr = new Label(address);
            // 颜色在 css/main.css 的 .nav-addr 定义（选中行时变白，内联样式无法被 CSS 覆盖）
            addr.getStyleClass().add("nav-addr");
            addr.setMaxWidth(110); // 超长地址省略号截断
            HBox statusRow = new HBox(6, dot, addr);
            statusRow.setAlignment(Pos.CENTER_LEFT);
            right = new VBox(4, name, statusRow);
        } else {
            right = new VBox(4, name, dot);
        }
        right.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(right, Priority.ALWAYS);

        HBox card = new HBox(10, iconBox, right);
        card.setPadding(new Insets(4, 8, 4, 8));
        return card;
    }

    /** 提取连接地址用于卡片展示：host:port 优先，其次各 MQ 的地址字段 */
    private String addressOf(MQConnectionConfig cfg) {
        String host = cfg.getProperty("host");
        String port = cfg.getProperty("port");
        if (host != null && !host.isBlank()) {
            return port != null && !port.isBlank() ? host + ":" + port : host;
        }
        for (String key : new String[]{"bootstrapServers", "namesrvAddr", "queueManager", "virtualHost"}) {
            String v = cfg.getProperty(key);
            if (v != null && !v.isBlank()) return v;
        }
        return "";
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
