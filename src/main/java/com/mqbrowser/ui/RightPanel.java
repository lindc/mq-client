package com.mqbrowser.ui;

import com.mqbrowser.model.TableRowItem;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TabPane;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * 右侧面板。外层面板中放置一个稍小的内层面板（通过内边距缩进），
 * 内层是一个 TabPane（多个标签页）。每个标签页展示一个 TableView，
 * 带表头、示例数据，并为每一行提供右键菜单。
 */
public class RightPanel {

    private final BorderPane outer = new BorderPane();

    public RightPanel() {
        // 内层面板：装载多标签页的表格
        StackPane inner = new StackPane(buildTabs());

        // 用 10px 内边距把内层面板往里缩，使其显得比外层小一圈
        BorderPane innerHolder = new BorderPane(inner);
        innerHolder.setPadding(new Insets(10));

        outer.setCenter(innerHolder);
    }

    /** 构建标签页容器，添加三个示例标签页。 */
    private TabPane buildTabs() {
        TabPane tabPane = new TabPane();
        // 禁止关闭标签页
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        tabPane.getTabs().addAll(
                buildTableTab("Messages", sampleMessages()),
                buildTableTab("Headers", sampleHeaders()),
                buildTableTab("Properties", sampleProperties()));

        return tabPane;
    }

    /** 构建一个标签页：内含一个三列表格。 */
    private Tab buildTableTab(String tabName, ObservableList<TableRowItem> data) {
        TableView<TableRowItem> table = new TableView<>(data);
        // 列宽自适应，最后一列填充剩余空间
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        // 应用样式类（边框 + 斑马纹，见 app.css 中 .data-table 规则）
        table.getStyleClass().add("data-table");

        // 三列，分别绑定 TableRowItem 的 name / type / value 属性
        TableColumn<TableRowItem, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("name"));

        TableColumn<TableRowItem, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(new PropertyValueFactory<>("type"));

        TableColumn<TableRowItem, String> valueCol = new TableColumn<>("Value");
        valueCol.setCellValueFactory(new PropertyValueFactory<>("value"));

        table.getColumns().add(nameCol);
        table.getColumns().add(typeCol);
        table.getColumns().add(valueCol);

        // 自定义行工厂：用于给每一行挂载右键菜单
        table.setRowFactory(tv -> createRow(table));

        return new Tab(tabName, table);
    }

    /** 创建一个表格行，并为其挂载右键菜单（仅非空行显示）。 */
    private TableRow<TableRowItem> createRow(TableView<TableRowItem> table) {
        TableRow<TableRowItem> row = new TableRow<>();

        // 构建行右键菜单
        ContextMenu menu = new ContextMenu();
        MenuItem view = new MenuItem("View");
        MenuItem copy = new MenuItem("Copy value");
        MenuItem delete = new MenuItem("Delete");
        view.setOnAction(e -> System.out.println("View: " + row.getItem().getName()));
        copy.setOnAction(e -> System.out.println("Copy: " + row.getItem().getValue()));
        delete.setOnAction(e -> table.getItems().remove(row.getItem()));   // 删除：从表格数据中移除该行
        menu.getItems().addAll(view, copy, new SeparatorMenuItem(), delete);

        // 通过绑定实现：空行不挂菜单，非空行挂菜单
        row.contextMenuProperty().bind(
                javafx.beans.binding.Bindings.when(row.emptyProperty())
                        .then((ContextMenu) null)
                        .otherwise(menu));

        return row;
    }

    // ===== 以下为三个标签页的示例数据 =====

    private ObservableList<TableRowItem> sampleMessages() {
        return FXCollections.observableArrayList(
                new TableRowItem("msg-1", "text", "Hello world"),
                new TableRowItem("msg-2", "json", "{\"id\":42}"),
                new TableRowItem("msg-3", "text", "Ping"));
    }

    private ObservableList<TableRowItem> sampleHeaders() {
        return FXCollections.observableArrayList(
                new TableRowItem("content-type", "string", "application/json"),
                new TableRowItem("priority", "int", "5"),
                new TableRowItem("persistent", "bool", "true"));
    }

    private ObservableList<TableRowItem> sampleProperties() {
        return FXCollections.observableArrayList(
                new TableRowItem("durable", "bool", "true"),
                new TableRowItem("auto-delete", "bool", "false"),
                new TableRowItem("max-length", "int", "10000"));
    }

    /** 返回该面板的根节点，供上层布局使用。 */
    public Region getNode() {
        return outer;
    }
}
