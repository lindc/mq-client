package io.mqclient.core.ui;

import io.mqclient.core.config.ConnectionStore;
import io.mqclient.core.model.MQConnectionConfig;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

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

        // 布局
        Label header = new Label("  连接列表");
        header.setStyle("-fx-font-size: 13px; -fx-padding: 8 4 4 8; -fx-text-fill: #666;");

        VBox.setVgrow(treeView, Priority.ALWAYS);
        this.view = new VBox(header, treeView);

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

        MenuItem editItem = new MenuItem("编辑连接");
        editItem.setOnAction(e -> {
            TreeItem<Item> sel = treeView.getSelectionModel().getSelectedItem();
            if (sel != null && sel.getValue() != null && !sel.getValue().isGroup()) {
                // TODO: 打开编辑对话框
            }
        });

        MenuItem deleteItem = new MenuItem("删除连接");
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
