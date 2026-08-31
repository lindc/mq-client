package org.bear.mqclient.app;

import atlantafx.base.controls.Card;
import atlantafx.base.controls.CustomTextField;
import atlantafx.base.controls.Spacer;
import atlantafx.base.controls.Tab;
import atlantafx.base.controls.TabLine;
import atlantafx.base.controls.ToggleSwitch;
import atlantafx.base.theme.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 应用入口与 UI 骨架：菜单栏 / 工具栏 / 左侧导航树 / 中间工作区 / 右侧 AI 面板 / 底部状态栏。
 * <p>
 * 当前为静态骨架 + 假数据，尚未接入 SPI 与真实连接。
 */
public class App extends Application {

    /** AtlantaFX 根字号默认 14px，这里整体调小一档。 */
    private static final double DEFAULT_FONT_SIZE = 12;

    /** 菜单中可切换的 AtlantaFX 主题（顺序即菜单顺序）。 */
    private static final Theme[] THEMES = {
            new PrimerLight(),
            new PrimerDark(),
            new NordDark(),
            new NordLight(),
            new Dracula(),
            new CupertinoLight(),
            new CupertinoDark()
    };

    private static final Theme DEFAULT_THEME = THEMES[0];

    /** -Dmq.atlantafx=false 时用原生 Modena 观感，方便与 AtlantaFX 对比。 */
    private static final boolean USE_ATLANTAFX = !"false".equalsIgnoreCase(System.getProperty("mq.atlantafx"));

    private final BorderPane root = new BorderPane();
    private Theme theme = DEFAULT_THEME;
    private double fontSize = DEFAULT_FONT_SIZE;

    /** 主题菜单项索引：applyTheme 时同步选中态。 */
    private final Map<Theme, RadioMenuItem> themeItems = new LinkedHashMap<>();

    private final TabLine tabs = new TabLine();
    private final StackPane tabContent = new StackPane();
    private final Map<Tab, Node> panes = new LinkedHashMap<>();
    private final AiPanel aiPanel = new AiPanel();

    private NavPanel navPanel;
    private Node nav;
    private Node aiPanelView;
    private Node queryPanel;
    private CheckMenuItem queryMenuItem;
    private CheckMenuItem navMenuItem;
    private CheckMenuItem aiMenuItem;
    private Label statusLabel;
    private Label summaryLabel;

    @Override
    public void start(Stage stage) {
        applyTheme(theme);
        applyFontSize();

        navPanel = new NavPanel(DemoData.CONNECTIONS, this::openObjectTab);
        nav = navPanel.getView();
        nav.getStyleClass().add(Styles.BG_INSET);
        bordered(nav, "0 1 0 0");
        navPanel.setOnCollapsedChange(collapsed -> navMenuItem.setSelected(!collapsed));

        aiPanelView = aiPanel.getView();
        aiPanelView.getStyleClass().add(Styles.BG_INSET);
        bordered(aiPanelView, "0 0 0 1");
        aiPanel.setOnCollapsedChange(collapsed -> aiMenuItem.setSelected(!collapsed));

        Node workspace = createWorkspace();
        workspace.getStyleClass().add(Styles.BG_DEFAULT);

        root.setTop(createMenuBar());
        root.setLeft(nav);
        root.setCenter(workspace);
        root.setRight(aiPanelView);
        root.setBottom(createStatusBar());

        stage.setTitle("MQ Client");
        stage.initStyle(StageStyle.DECORATED);
        stage.setResizable(true);
        stage.setScene(new Scene(root, 1280, 720));
        stage.setMinWidth(1024);
        stage.setMinHeight(640);
        stage.show();

        // 从后台/最小化控制台启动时，Windows 会把"最小化"状态继承给窗口，
        // 导致窗口一开就缩在任务栏里（标题栏与最大/最小化按钮都看不到）。这里强制还原。
        stage.setIconified(false);
        stage.toFront();
        stage.requestFocus();
    }

    // ---------- 菜单栏 ----------

    private MenuBar createMenuBar() {
        MenuItem exit = new MenuItem("退出");
        exit.setOnAction(e -> Platform.exit());

        Menu file = new Menu("文件");
        file.getItems().setAll(
                item("新建连接", "新建连接（未接入）"),
                item("导入连接…", "导入连接（未接入）"),
                item("导出连接…", "导出连接（未接入）"),
                new SeparatorMenuItem(),
                exit
        );

        Menu edit = new Menu("编辑");
        edit.getItems().setAll(
                item("查找", "查找（未接入）"),
                item("偏好设置", "偏好设置（未接入）")
        );

        queryMenuItem = new CheckMenuItem("显示查询面板");
        queryMenuItem.setSelected(false);
        queryMenuItem.setOnAction(e -> {
            boolean show = queryMenuItem.isSelected();
            queryPanel.setVisible(show);
            queryPanel.setManaged(show);
        });

        navMenuItem = new CheckMenuItem("显示导航");
        navMenuItem.setSelected(true);
        navMenuItem.setOnAction(e -> navPanel.setCollapsed(!navMenuItem.isSelected()));

        aiMenuItem = new CheckMenuItem("显示 AI 面板");
        aiMenuItem.setSelected(true);
        aiMenuItem.setOnAction(e -> aiPanel.setCollapsed(!aiMenuItem.isSelected()));

        // 主题切换：7 个 AtlantaFX 主题，单选互斥
        Menu themeMenu = new Menu("主题");
        ToggleGroup themeGroup = new ToggleGroup();
        for (Theme t : THEMES) {
            RadioMenuItem item = new RadioMenuItem(t.getName());
            item.setToggleGroup(themeGroup);
            item.setSelected(t == theme);
            item.setOnAction(e -> applyTheme(t));
            themeMenu.getItems().add(item);
            themeItems.put(t, item);
        }

        MenuItem zoomIn = new MenuItem("增大字号");
        zoomIn.setOnAction(e -> changeFontSize(1));
        MenuItem zoomOut = new MenuItem("减小字号");
        zoomOut.setOnAction(e -> changeFontSize(-1));
        MenuItem zoomReset = new MenuItem("重置字号");
        zoomReset.setOnAction(e -> {
            fontSize = DEFAULT_FONT_SIZE;
            applyFontSize();
        });

        Menu view = new Menu("视图");
        view.getItems().setAll(
                navMenuItem,
                aiMenuItem,
                themeMenu,
                queryMenuItem,
                item("刷新", "刷新（未接入）"),
                new SeparatorMenuItem(),
                zoomIn,
                zoomOut,
                zoomReset
        );

        MenuItem about = new MenuItem("关于");
        about.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION,
                "MQ Client — JavaFX 26 + AtlantaFX 2.1.0").showAndWait());
        Menu help = new Menu("帮助");
        help.getItems().setAll(about);

        return new MenuBar(file, edit, view, help);
    }

    private MenuItem item(String text, String statusText) {
        MenuItem menuItem = new MenuItem(text);
        menuItem.setOnAction(e -> setStatus(statusText));
        return menuItem;
    }

    private void applyTheme(Theme next) {
        theme = next;
        Application.setUserAgentStylesheet(USE_ATLANTAFX
                ? next.getUserAgentStylesheet()
                : Application.STYLESHEET_MODENA);
        // 同步菜单选中态（菜单未创建时 themeItems 为空，跳过）
        RadioMenuItem item = themeItems.get(next);
        if (item != null) {
            item.setSelected(true);
        }
        refreshSummary();
    }

    private void changeFontSize(double delta) {
        fontSize = Math.max(9, Math.min(20, fontSize + delta));
        applyFontSize();
    }

    private void applyFontSize() {
        root.setStyle("-fx-font-size: " + fontSize + "px;");
        refreshSummary();
    }

    // ---------- 中间工作区 ----------

    private Node createWorkspace() {
        tabs.getStyleClass().add(Tweaks.EDGE_TO_EDGE);
        tabs.setTabClosingPolicy(Tab.ClosingPolicy.ALL_TABS);

        addFixedTab("概览", createOverview());
        addFixedTab("发布", createPublish());
        addFixedTab("样式总览", StyleGallery.create());

        tabs.getSelectionModel().selectFirst();

        queryPanel = createQueryPanel();

        // 中间内容面板：AtlantaFX 风格容器（BG_INSET 背景 + 边框），
        // Tab 与内容离面板边框留出间距；外层再留一圈，不贴窗口边缘。
        VBox panel = new VBox(10, tabs, queryPanel, tabContent);
        panel.setPadding(new Insets(12, 14, 14, 14));
        panel.getStyleClass().addAll(Styles.BG_INSET, Styles.BORDER_DEFAULT);
        VBox.setVgrow(tabContent, Priority.ALWAYS);

        VBox box = new VBox(panel);
        box.setPadding(new Insets(8));
        box.getStyleClass().add(Styles.BG_DEFAULT);
        return box;
    }

    /** 中间工作区的查询面板，默认隐藏，由「视图 → 显示查询面板」切换。 */
    private Node createQueryPanel() {
        Label title = new Label("查询条件");
        title.getStyleClass().add(Styles.TITLE_4);

        CustomTextField keyword = new CustomTextField();
        keyword.setPromptText("关键字");
        keyword.setLeft(new Label("🔍"));
        keyword.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(keyword, Priority.ALWAYS);

        ComboBox<String> field = new ComboBox<>(
                FXCollections.observableArrayList("全部字段", "消息 ID", "消息内容"));
        field.getSelectionModel().selectFirst();

        TextField from = new TextField();
        from.setPromptText("开始时间");
        TextField to = new TextField();
        to.setPromptText("结束时间");

        Button query = new Button("查询");
        query.getStyleClass().add(Styles.ACCENT);
        query.setOnAction(e -> setStatus("查询（未接入）"));
        Button reset = new Button("重置");

        HBox firstRow = new HBox(8, keyword, field);
        firstRow.setAlignment(Pos.CENTER_LEFT);
        HBox secondRow = new HBox(8, from, to, spacer(), query, reset);
        secondRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8, title, firstRow, secondRow);
        box.setPadding(new Insets(12));
        box.getStyleClass().addAll(Styles.BG_INSET, Styles.BORDER_DEFAULT);
        box.setVisible(false);
        box.setManaged(false);
        return box;
    }

    private void addFixedTab(String text, Node content) {
        Tab tab = new Tab(text);
        tab.setPinned(true);
        tab.setOnSelectionChanged(e -> showOnly(tabContent, content));
        tabs.getTabs().add(tab);
        panes.put(tab, content);
        tabContent.getChildren().add(content);
    }

    /** 点左侧三级（对象类型）时调用：已开过就选中，否则新增一个可关闭的 Tab。 */
    private void openObjectTab(NavPanel.ObjectType objectType) {
        String id = objectType.connName() + " · " + objectType.type();

        for (Map.Entry<Tab, Node> entry : panes.entrySet()) {
            if (id.equals(entry.getKey().getUserData())) {
                tabs.getSelectionModel().select(entry.getKey());
                return;
            }
        }

        Node content = createObjectContent(objectType);
        Tab tab = new Tab(id);
        tab.setUserData(id);
        tab.setOnSelectionChanged(e -> showOnly(tabContent, content));
        tab.setOnClosed(e -> {
            tabContent.getChildren().remove(content);
            panes.remove(tab);
        });

        tabs.getTabs().add(tab);
        panes.put(tab, content);
        tabContent.getChildren().add(content);
        tabs.getSelectionModel().select(tab);
        showOnly(tabContent, content);

        setStatus("已打开 " + id);
        aiPanel.show("当前对象：" + id + "，共 " + objectType.dests().size() + " 个");
    }

    /** 对象类型 Tab 的内容：上方对象表格，下方详情面板。 */
    private Node createObjectContent(NavPanel.ObjectType objectType) {
        TableView<DemoData.Dest> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getItems().setAll(objectType.dests());
        table.getColumns().setAll(
                column("名称", DemoData.Dest::name, 320),
                column("类型", DemoData.Dest::type, 100),
                column("消息数", d -> String.valueOf(fakeCount(d.name())), 100)
        );

        GridPane detailGrid = new GridPane();
        detailGrid.setHgap(12);
        detailGrid.setVgap(6);
        detailGrid.setPadding(new Insets(8));

        VBox detailBox = new VBox(8, styled(new Label("详情"), Styles.TITLE_4), detailGrid);
        detailBox.setPadding(new Insets(16));
        VBox.setVgrow(detailGrid, Priority.ALWAYS);

        table.getSelectionModel().selectedItemProperty().addListener((obs, oldDest, dest) -> {
            if (dest != null) {
                showDetail(detailGrid, objectType.connName(), dest);
                setStatus("已选择 " + dest.type() + " " + dest.name());
                aiPanel.show("已选择 " + dest.type() + " " + dest.name());
            }
        });
        table.getSelectionModel().selectFirst();

        Label heading = new Label(objectType.connName() + " · " + objectType.type());
        heading.getStyleClass().add(Styles.TITLE_4);

        Button refresh = new Button("刷新");
        Button browse = new Button("浏览消息");
        browse.getStyleClass().add(Styles.ACCENT);
        browse.setOnAction(e -> setStatus("浏览消息（未接入）"));

        HBox toolRow = new HBox(8, heading, spacer(), refresh, browse);
        toolRow.setAlignment(Pos.CENTER_LEFT);

        SplitPane split = new SplitPane(table, detailBox);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.6);

        VBox box = new VBox(8, toolRow, split);
        box.setPadding(new Insets(16));
        VBox.setVgrow(split, Priority.ALWAYS);
        return box;
    }

    private void showDetail(GridPane grid, String connName, DemoData.Dest dest) {
        grid.getChildren().clear();
        addDetailRow(grid, 0, "名称", dest.name());
        addDetailRow(grid, 1, "类型", dest.type());
        addDetailRow(grid, 2, "所属连接", connName);
        addDetailRow(grid, 3, "消息数", String.valueOf(fakeCount(dest.name())));
        addDetailRow(grid, 4, "说明", "演示数据，接入 SPI 后此处显示真实指标");
    }

    private void addDetailRow(GridPane grid, int row, String key, String value) {
        Label keyLabel = styled(new Label(key), Styles.TEXT_MUTED);
        keyLabel.setMinWidth(90);
        grid.add(keyLabel, 0, row);
        grid.add(new Label(value), 1, row);
    }

    private Node createOverview() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(16));

        ColumnConstraints left = new ColumnConstraints();
        ColumnConstraints right = new ColumnConstraints();
        left.setPercentWidth(50);
        right.setPercentWidth(50);
        grid.getColumnConstraints().setAll(left, right);

        grid.add(statCard("队列 / Topic", String.valueOf(countDestinations())), 0, 0);
        grid.add(statCard("积压消息", "1,284"), 1, 0);
        grid.add(statCard("今日入队", "8.7k"), 0, 1);
        grid.add(statCard("今日出队", "8.6k"), 1, 1);
        return grid;
    }

    private Card statCard(String caption, String value) {
        Card card = new Card();
        card.setHeader(styled(new Label(caption), Styles.TEXT_CAPTION, Styles.TEXT_MUTED));
        card.setBody(styled(new Label(value), Styles.TITLE_1));
        return card;
    }

    private Node createPublish() {
        ComboBox<String> target = new ComboBox<>(
                FXCollections.observableArrayList("ORDER.PAID", "ORDER.CREATED", "ORDER.SHIPPED"));
        target.getSelectionModel().selectFirst();

        TextArea body = new TextArea("{\n  \"orderId\": 10024,\n  \"status\": \"CREATED\"\n}");
        body.setPrefRowCount(8);
        VBox.setVgrow(body, Priority.ALWAYS);

        Button send = new Button("发送");
        send.getStyleClass().add(Styles.ACCENT);
        send.setOnAction(e -> setStatus("已发送 1 条消息（未接入）"));

        HBox footer = new HBox(8, spacer(), send);
        footer.setAlignment(Pos.CENTER_RIGHT);

        VBox box = new VBox(12, new Label("目标队列"), target, new Label("消息内容"), body, footer);
        box.setPadding(new Insets(16));
        return box;
    }

    // ---------- 底部状态栏 ----------

    private Node createStatusBar() {
        statusLabel = new Label("就绪");
        statusLabel.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);

        summaryLabel = new Label();
        summaryLabel.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);
        refreshSummary();

        HBox bar = new HBox(12, statusLabel, spacer(), summaryLabel);
        bar.setPadding(new Insets(6, 12, 6, 12));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add(Styles.BG_INSET);
        return bordered(bar, "1 0 0 0");
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    private void refreshSummary() {
        if (summaryLabel == null) {
            return;
        }
        long connected = DemoData.CONNECTIONS.stream().filter(DemoData.Conn::connected).count();
        summaryLabel.setText(DemoData.CONNECTIONS.size() + " 个连接 · " + connected + " 已连接 · "
                + countDestinations() + " 个对象 · "
                + (USE_ATLANTAFX ? theme.getName() : "Modena（未启用 AtlantaFX）")
                + " · " + (int) fontSize + "px");
    }

    // ---------- 工具 ----------

    private void showOnly(StackPane container, Node pane) {
        container.getChildren().forEach(n -> {
            n.setVisible(n == pane);
            n.setManaged(n == pane);
        });
    }

    private TableColumn<DemoData.Dest, String> column(String title,
                                                      Function<DemoData.Dest, String> getter,
                                                      double width) {
        TableColumn<DemoData.Dest, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(c -> new SimpleStringProperty(getter.apply(c.getValue())));
        return col;
    }

    /** 给面板加 1px 分隔线：颜色用主题的 border-default，方向靠内联 border-width 控制。 */
    private static <T extends Node> T bordered(T node, String widths) {
        node.getStyleClass().add(Styles.BORDER_DEFAULT);
        node.setStyle("-fx-border-width: " + widths + ";");
        return node;
    }

    /** 演示用的假消息数：同一名称始终得到同一个值。 */
    private static int fakeCount(String name) {
        return Math.abs(name.hashCode() % 500);
    }

    private static int countDestinations() {
        return DemoData.CONNECTIONS.stream().mapToInt(c -> c.dests().size()).sum();
    }

    private static Spacer spacer() {
        Spacer spacer = new Spacer();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private static <T extends Node> T styled(T node, String... styleClasses) {
        node.getStyleClass().addAll(styleClasses);
        return node;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
