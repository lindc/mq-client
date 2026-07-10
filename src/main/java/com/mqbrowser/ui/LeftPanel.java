package com.mqbrowser.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * 左侧面板，采用两层结构：
 * <ul>
 *   <li>外层（底层）面板 {@code root}：跟随 SplitPane 一起调整大小，四周留出内边距；</li>
 *   <li>内层卡片面板 {@code card}：占满外层（除去内边距），带阴影和圆角，形成悬浮卡片，
 *       内部放置标题 + 列表。</li>
 * </ul>
 * 列表项支持右键菜单，菜单按单元格（cell）挂载，只在有内容的行上出现。
 */
public class LeftPanel {

    // 外层（底层）面板：负责随 SplitPane 调整大小，并提供内边距
    private final BorderPane root = new BorderPane();
    // 内层卡片面板：占满外层，带阴影/圆角，承载标题和列表
    private final BorderPane card = new BorderPane();
    // 列表数据源（示例数据），ObservableList 变化时列表会自动刷新
    private final ObservableList<String> items = FXCollections.observableArrayList(
            "Connection A", "Connection B", "Connection C", "Connection D");
    private final ListView<String> listView = new ListView<>(items);

    public LeftPanel() {
        // 顶部标题
        Label title = new Label("Connections");
        title.setPadding(new Insets(8, 10, 8, 10));
        title.setStyle("-fx-font-weight: bold;");

        listView.getStyleClass().add("nav-list");
        // 自定义单元格：用于显示图标 + 右键菜单
        listView.setCellFactory(lv -> createCell());

        // 内层卡片：装标题 + 列表，样式（背景/圆角/阴影）见 app.css 的 ".nav-card"
        card.setTop(title);
        card.setCenter(listView);
        card.getStyleClass().add("nav-card");

        // 外层面板：四周留 8px 内边距，让内层卡片的阴影有空间显示
        root.setCenter(card);
        root.setPadding(new Insets(8));
        root.setMinWidth(140);
        // 外层背景色来自 app.css 中的 ".nav-panel" 规则（引用 -c-left-panel-bg 调色板变量）
        root.getStyleClass().add("nav-panel");
    }

    /** 创建一个列表单元格：显示文字 + 前置图标，并挂载右键菜单。 */
    private ListCell<String> createCell() {
        ListCell<String> cell = new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    // 空白行：不显示文字和图标
                    setText(null);
                    setGraphic(null);
                } else {
                    // 有内容的行：显示文字，并在前面加一个蓝色小圆点作为图标
                    setText(item);
                    setGraphic(new Circle(5, Color.web("#4a90d9")));
                }
            }
        };

        // 构建右键菜单
        ContextMenu menu = new ContextMenu();
        MenuItem open = new MenuItem("Open");
        MenuItem rename = new MenuItem("Rename");
        MenuItem remove = new MenuItem("Delete");
        open.setOnAction(e -> System.out.println("Open: " + cell.getItem()));
        rename.setOnAction(e -> System.out.println("Rename: " + cell.getItem()));
        remove.setOnAction(e -> items.remove(cell.getItem()));   // 删除：从数据源移除该项
        menu.getItems().addAll(open, rename, new SeparatorMenuItem(), remove);

        // 仅在非空单元格上显示右键菜单：空白行 -> 不挂菜单
        cell.emptyProperty().addListener((obs, wasEmpty, isEmpty) ->
                cell.setContextMenu(isEmpty ? null : menu));

        return cell;
    }

    /** 返回该面板的根节点，供上层布局使用。 */
    public Region getNode() {
        return root;
    }
}
