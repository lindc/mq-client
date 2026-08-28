package org.bear.mqclient.core.ui;

import org.bear.mqclient.core.config.ConnectionStore;
import org.bear.mqclient.core.model.MQConnectionConfig;
import org.bear.mqclient.core.util.I18n;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 左侧连接导航面板。
 * <p>
 * 使用 TreeView 按 MQ 类型分组展示所有配置的连接。
 */
public class NavigationPanel {

    private static final String GROUP_ICON = "📁";
    private static final Map<String, String> TYPE_ICONS = Map.of(
            "IBM_MQ", "🔵",
            "KAFKA", "⚡",
            "RABBITMQ", "🐇",
            "ARTEMIS", "🔶",
            "ROCKETMQ", "☄️"
    );

    private final VBox view;
    private final TreeView<Item> treeView;
    private final TreeItem<Item> rootItem;
    private final ConnectionStore store;
    private final Consumer<MQConnectionConfig> onSelect;

    private record Item(String label, String type, MQConnectionConfig config) {
        boolean isGroup() { return config == null; }
        @Override public String toString() {
            return isGroup() ? label : icon(type) + "  " + label;
        }
        private static String icon(String type) {
            return TYPE_ICONS.getOrDefault(type, "🔌");
        }
    }

    public NavigationPanel(ConnectionStore store, Consumer<MQConnectionConfig> onSelect) {
        this.store = store;
        this.onSelect = onSelect;

        // TreeView
        this.rootItem = new TreeItem<>(new Item("Root", null, null));
        rootItem.setExpanded(true);

        this.treeView = new TreeView<>(rootItem);
        treeView.setShowRoot(false);
        treeView.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(Item item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item.toString());
                    // 分组用粗体
                    if (item.isGroup()) setStyle("-fx-font-weight: bold;");
                    else setStyle("");
                }
            }
        });

        // 点击选择
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null && sel.getValue() != null && !sel.getValue().isGroup()) {
                onSelect.accept(sel.getValue().config());
            }
        });

        // 右键菜单
        ContextMenu ctxMenu = createContextMenu();
        treeView.setContextMenu(ctxMenu);

        // 布局：无标题文字；面板带背景色 + 圆角 + 阴影，呈悬浮卡片效果
        // （阴影需配合 SplitPane 中 navWrap 留出的空隙与同色背景才能完整可见）
        VBox.setVgrow(treeView, Priority.ALWAYS);
        // TreeView 背景透明，圆角才能透出面板背景
        treeView.setStyle("-fx-background-color: transparent;");
        // TreeView 内容圆角裁剪：避免直角内容把阴影形状顶出尖角。
        // 注意：不能裁剪 view 本身——clip 作用于节点全部渲染，会把溢出面板边界的阴影一并裁掉
        Rectangle clip = new Rectangle();
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        clip.widthProperty().bind(treeView.widthProperty());
        clip.heightProperty().bind(treeView.heightProperty());
        treeView.setClip(clip);
        this.view = new VBox(treeView);
        // 面板白色、背景灰（#e8eaed）：色差 + 阴影形成悬浮卡片；阴影只向下偏移避免右缘灰带
        view.setStyle("-fx-background-color: #ffffff;"
                + " -fx-background-radius: 12;"
                + " -fx-effect: dropshadow(gaussian, rgba(70, 110, 160, 0.4), 18, 0.2, 0, 5);");
        view.setMinWidth(140);

        // 初始加载
        refresh();

        // 监听配置变化
        store.addChangeListener(this::refresh);
    }

    public Node getView() { return view; }

    public void refresh() {
        rootItem.getChildren().clear();

        // 按 type 分组
        Map<String, List<MQConnectionConfig>> grouped = store.listAll()
                .stream()
                .collect(Collectors.groupingBy(MQConnectionConfig::getType, LinkedHashMap::new, Collectors.toList()));

        for (var entry : grouped.entrySet()) {
            String type = entry.getKey();
            List<MQConnectionConfig> configs = entry.getValue();

            // 分组节点（图标 + 类型名）
            String groupLabel = TYPE_ICONS.getOrDefault(type, "🔌") + "  " + type;
            TreeItem<Item> groupItem = new TreeItem<>(new Item(groupLabel, type, null));
            groupItem.setExpanded(true);

            for (MQConnectionConfig cfg : configs) {
                TreeItem<Item> leaf = new TreeItem<>(new Item(cfg.getName(), type, cfg));
                groupItem.getChildren().add(leaf);
            }

            rootItem.getChildren().add(groupItem);
        }
    }

    private ContextMenu createContextMenu() {
        ContextMenu menu = new ContextMenu();

        MenuItem editItem = new MenuItem(I18n.get("navigation.edit"));
        editItem.setOnAction(e -> {
            TreeItem<Item> sel = treeView.getSelectionModel().getSelectedItem();
            if (sel != null && sel.getValue() != null && !sel.getValue().isGroup()) {
                // TODO: 打开编辑对话框
            }
        });

        MenuItem deleteItem = new MenuItem(I18n.get("navigation.delete"));
        deleteItem.setOnAction(e -> {
            TreeItem<Item> sel = treeView.getSelectionModel().getSelectedItem();
            if (sel != null && sel.getValue() != null && !sel.getValue().isGroup()) {
                store.remove(sel.getValue().config().getId());
            }
        });

        menu.getItems().addAll(editItem, deleteItem);
        return menu;
    }
}
