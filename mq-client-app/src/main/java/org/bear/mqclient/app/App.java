package org.bear.mqclient.app;

import atlantafx.base.controls.CaptionMenuItem;
import atlantafx.base.controls.ModalPane;
import atlantafx.base.controls.Spacer;
import atlantafx.base.theme.CupertinoDark;
import atlantafx.base.theme.CupertinoLight;
import atlantafx.base.theme.Dracula;
import atlantafx.base.theme.NordDark;
import atlantafx.base.theme.NordLight;
import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import atlantafx.base.theme.Styles;
import atlantafx.base.theme.Theme;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import java.util.List;
import javafx.scene.control.Alert;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * 应用入口与主窗口骨架：菜单栏 / 左侧导航 / 中间工作区 / 底部状态栏。
 * <p>
 * 左侧栏仿 AtlantaFX Sampler；中间工作区目前为空，待接入 SPI 后填充。
 */
public class App extends Application {

    /** 全屏模态层的 id，弹窗通过 {@code #modal-pane} 选择器找到它。 */
    static final String MODAL_PANE_ID = "modal-pane";

    private static final double DEFAULT_FONT_SIZE = 11;

    private static final Theme[] THEMES = {
            new PrimerLight(),
            new PrimerDark(),
            new NordLight(),
            new NordDark(),
            new Dracula(),
            new CupertinoLight(),
            new CupertinoDark()
    };

    private final StackPane root = new StackPane();
    private final BorderPane content = new BorderPane();

    private Sidebar sidebar;
    private Label statusLabel;
    private Label summaryLabel;
    private CheckMenuItem navMenuItem;
    private int themeIndex;
    private double fontSize = DEFAULT_FONT_SIZE;
    private Scene scene;

    @Override
    public void start(Stage stage) {
        sidebar = new Sidebar(this::openPage, this::openThemeDialog);

        var modalPane = new ModalPane();
        modalPane.setId(MODAL_PANE_ID);

        content.setTop(createMenuBar());
        content.setLeft(sidebar);
        content.setCenter(createWorkspace());
        content.setBottom(createStatusBar());

        root.getChildren().setAll(modalPane, content);

        applyTheme();
        applyFontSize();

        stage.setTitle("MQ Client");
        scene = new Scene(root, 1280, 720);
        scene.getStylesheets().add(App.class.getResource("app.css").toExternalForm());
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.SLASH && !isTyping(e.getTarget())) {
                sidebar.openSearch();
            }
        });
        stage.setScene(scene);
        stage.setMinWidth(1024);
        stage.setMinHeight(640);
        stage.show();

        // 从后台/最小化控制台启动时，Windows 会把"最小化"状态继承给窗口，
        // 导致窗口一开就缩在任务栏里。这里强制还原。
        stage.setIconified(false);
        stage.toFront();
        stage.requestFocus();
    }

    // ---------- 菜单栏 ----------

    private MenuBar createMenuBar() {
        var exit = new MenuItem("退出");
        exit.setOnAction(e -> Platform.exit());

        var file = new Menu("文件");
        file.getItems().setAll(
                item("新建连接"),
                item("导入连接…"),
                item("导出连接…"),
                new SeparatorMenuItem(),
                exit
        );

        var edit = new Menu("编辑");
        edit.getItems().setAll(item("查找"), item("偏好设置"));

        navMenuItem = new CheckMenuItem("显示导航");
        navMenuItem.setSelected(true);
        navMenuItem.setOnAction(e -> {
            boolean show = navMenuItem.isSelected();
            sidebar.setVisible(show);
            sidebar.setManaged(show);
        });

        var view = new Menu("视图");
        view.getItems().setAll(
                new CaptionMenuItem("显示"),
                navMenuItem,
                new SeparatorMenuItem(),
                new CaptionMenuItem("字号"),
                zoomItem("增大字号", 1),
                zoomItem("减小字号", -1),
                zoomItem("重置字号", 0)
        );

        var about = new MenuItem("关于");
        about.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION,
                "MQ Client — JavaFX 26 + AtlantaFX 2.1.0").showAndWait());

        var help = new Menu("帮助");
        help.getItems().setAll(about);

        return new MenuBar(file, edit, view, help);
    }

    private MenuItem item(String text) {
        var menuItem = new MenuItem(text);
        menuItem.setOnAction(e -> setStatus(text + "（未接入）"));
        return menuItem;
    }

    private MenuItem zoomItem(String text, double delta) {
        var menuItem = new MenuItem(text);
        menuItem.setOnAction(e -> {
            fontSize = delta == 0 ? DEFAULT_FONT_SIZE
                    : Math.max(9, Math.min(20, fontSize + delta));
            applyFontSize();
        });
        return menuItem;
    }

    // ---------- 工作区与状态栏 ----------

    private Node createWorkspace() {
        var pane = new StackPane();
        pane.setId("content");
        return pane;
    }

    private Node createStatusBar() {
        statusLabel = new Label("就绪");
        statusLabel.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);

        summaryLabel = new Label();
        summaryLabel.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);

        var bar = new HBox(12, statusLabel, new Spacer(), summaryLabel);
        bar.setId("status-bar");
        bar.setPadding(new Insets(6, 12, 6, 12));
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    // ---------- 主题与字号 ----------

    /** 打开主题选择对话框（仿 AtlantaFX Sampler 的 ThemeDialog）。 */
    private void openThemeDialog() {
        var dialog = new ThemeDialog(List.of(THEMES), THEMES[themeIndex], theme -> {
            for (int i = 0; i < THEMES.length; i++) {
                if (THEMES[i].getName().equals(theme.getName())) {
                    themeIndex = i;
                    break;
                }
            }
            applyTheme();
        });
        dialog.show(scene);
    }

    private void applyTheme() {
        Application.setUserAgentStylesheet(THEMES[themeIndex].getUserAgentStylesheet());
        refreshSummary();
    }

    private void applyFontSize() {
        root.setStyle("-fx-font-size: " + fontSize + "px;");
        refreshSummary();
    }

    private void refreshSummary() {
        if (summaryLabel == null) {
            return;
        }
        summaryLabel.setText(THEMES[themeIndex].getName() + " · " + (int) fontSize + "px");
    }

    // ---------- 导航 ----------

    private void openPage(NavTree.Item item) {
        setStatus("已打开 " + item.getValue().title() + "（内容区待接入）");
    }

    /** 正在输入框里打字时不触发 "/" 快捷键。 */
    private static boolean isTyping(Object target) {
        return target instanceof TextInputControl;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
