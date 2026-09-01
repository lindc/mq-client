package org.bear.mqclient.app;

import atlantafx.base.controls.Spacer;
import atlantafx.base.theme.Styles;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2MZ;
import org.kordamp.ikonli.material2.Material2OutlinedAL;
import org.kordamp.ikonli.material2.Material2OutlinedMZ;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 左侧栏：Logo + 搜索按钮 + 导航树 + 底部版本。
 * <p>
 * 结构与样式仿 AtlantaFX Sampler 的 Sidebar。
 */
final class Sidebar extends VBox {

    static final double WIDTH = 270;

    private final NavTree navTree;
    private final List<NavTree.Item> pages = new ArrayList<>();
    private SearchDialog searchDialog;

    Sidebar(Consumer<NavTree.Item> onSelect, Runnable onOpenTheme) {
        navTree = new NavTree(onSelect);
        navTree.setRoot(createTree());
        VBox.setVgrow(navTree, Priority.ALWAYS);

        setId("sidebar");
        setMinWidth(WIDTH);
        setMaxWidth(WIDTH);
        getChildren().setAll(new Header(onOpenTheme), navTree);
    }

    void select(NavTree.Item item) {
        navTree.getSelectionModel().select(item);
    }

    void openSearch() {
        if (searchDialog == null) {
            searchDialog = new SearchDialog(pages, this::select);
        }
        searchDialog.show(getScene());
    }

    ///////////////////////////////////////////////////////////////////////////
    // 导航树内容                                                             //
    ///////////////////////////////////////////////////////////////////////////

    private NavTree.Item createTree() {
        var connections = NavTree.Item.group("连接", icon(Material2OutlinedMZ.SETTINGS_ETHERNET));
        connections.getChildren().setAll(
                page("全部连接", "connections"),
                page("新建连接", "connection-new")
        );
        connections.setExpanded(true);

        var destinations = NavTree.Item.group("队列与主题", icon(Material2OutlinedAL.LIST_ALT));
        destinations.getChildren().setAll(
                page("队列", "queues"),
                page("主题", "topics"),
                page("交换机", "exchanges")
        );

        var messages = NavTree.Item.group("消息", icon(Material2OutlinedAL.FORUM));
        messages.getChildren().setAll(
                page("浏览", "browse"),
                page("发布", "publish"),
                page("订阅", "subscribe")
        );

        var monitor = NavTree.Item.group("监控", icon(Material2OutlinedAL.ANALYTICS));
        monitor.getChildren().setAll(
                page("概览", "overview"),
                page("积压", "backlog")
        );

        var settings = NavTree.Item.group("设置", icon(Material2OutlinedMZ.SETTINGS));
        settings.getChildren().setAll(
                page("外观", "appearance"),
                page("日志", "logs")
        );

        var root = NavTree.Item.root();
        root.getChildren().setAll(connections, destinations, messages, monitor, settings);
        return root;
    }

    /** 建叶子节点并登记到 {@link #pages}，供搜索对话框过滤。 */
    private NavTree.Item page(String title, String pageId) {
        var item = NavTree.Item.page(title, pageId);
        pages.add(item);
        return item;
    }

    private static FontIcon icon(Ikon ikon) {
        return new FontIcon(ikon);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Header / Footer                                                       //
    ///////////////////////////////////////////////////////////////////////////

    private final class Header extends VBox {

        Header(Runnable onOpenTheme) {
            getStyleClass().add("header");
            getChildren().setAll(createLogo(onOpenTheme), createSearchButton());
        }

        private HBox createLogo(Runnable onOpenTheme) {
            var logoIcon = new FontIcon(Material2OutlinedAL.FORUM);
            logoIcon.setIconSize(24);

            var logoBox = new StackPane(logoIcon);
            logoBox.getStyleClass().add("image");
            logoBox.setPadding(new Insets(1));
            logoBox.setPrefSize(32, 32);
            logoBox.setMaxSize(32, 32);

            var title = new Label("MQ Client");
            title.getStyleClass().add(Styles.TITLE_3);

            var themeBtn = new Button();
            themeBtn.setGraphic(new FontIcon(Material2MZ.WB_SUNNY));
            themeBtn.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            themeBtn.getStyleClass().addAll("palette", Styles.BUTTON_CIRCLE, Styles.FLAT);
            themeBtn.setAlignment(Pos.CENTER_RIGHT);
            themeBtn.setTooltip(new Tooltip("选择主题"));
            themeBtn.setOnAction(e -> onOpenTheme.run());

            var root = new HBox(0, logoBox, title, new Spacer(), themeBtn);
            root.getStyleClass().add("logo");
            root.setAlignment(Pos.CENTER_LEFT);
            return root;
        }

        private Button createSearchButton() {
            var title = new Label("搜索", new FontIcon(Material2MZ.SEARCH));

            var hint = new Label("按 /");
            hint.getStyleClass().addAll("hint", Styles.TEXT_MUTED, Styles.TEXT_SMALL);

            var content = new HBox(title, new Spacer(), hint);
            content.getStyleClass().add("content");
            content.setAlignment(Pos.CENTER_LEFT);

            var button = new Button();
            button.setGraphic(content);
            button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            button.getStyleClass().add("search-button");
            button.setMaxWidth(Double.MAX_VALUE);
            button.setOnAction(e -> openSearch());
            return button;
        }
    }
}
