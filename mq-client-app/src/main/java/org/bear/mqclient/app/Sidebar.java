package org.bear.mqclient.app;

import atlantafx.base.controls.Spacer;
import atlantafx.base.theme.Styles;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.image.ImageView;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.bear.mqclient.core.spi.MQUIProvider;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2MZ;
import org.kordamp.ikonli.material2.Material2OutlinedAL;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.Consumer;

/**
 * 左侧栏：Logo + 搜索按钮 + 导航树（按 MQ 类型分组的品牌图标）。
 * <p>
 * 结构与样式仿 AtlantaFX Sampler 的 Sidebar。导航项由各 MQ 的 UI 模块通过
 * {@link MQUIProvider#getIcon()} 提供品牌图片，而非字体图标。
 */
final class Sidebar extends VBox {

    /** 默认/首选宽度；用户可通过 SplitPane 分隔条拖动调整。 */
    static final double WIDTH = 270;
    private static final double MIN_WIDTH = 200;
    private static final double MAX_WIDTH = 460;

    /** 导航展示顺序（按 MQ 类型），其余类型排在其后。 */
    private static final List<String> PREFERRED_ORDER = List.of(
            "KAFKA", "RABBITMQ", "IBM_MQ", "ARTEMIS", "ROCKETMQ");

    private final NavTree navTree;
    private final List<NavTree.Item> pages = new ArrayList<>();
    private SearchDialog searchDialog;

    Sidebar(Consumer<NavTree.Item> onSelect, Runnable onOpenTheme) {
        navTree = new NavTree(onSelect);
        navTree.setRoot(createTree());
        VBox.setVgrow(navTree, Priority.ALWAYS);

        setId("sidebar");
        setMinWidth(MIN_WIDTH);
        setPrefWidth(WIDTH);
        setMaxWidth(MAX_WIDTH);
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

    /**
     * 通过 SPI 发现所有 MQ UI 提供者，按固定顺序为每个 MQ 构建一个可展开分组，
     * 分组的品牌图片来自 {@link MQUIProvider#getIcon()}，其下的子页面来自
     * {@link MQUIProvider#getNavPages()}（由各 MQ 的 UI 模块自行决定）。
     */
    private NavTree.Item createTree() {
        var root = NavTree.Item.root();

        var groups = ServiceLoader.load(MQUIProvider.class).stream()
                .map(provider -> provider.get())
                .sorted(Comparator.comparingInt(p -> {
                    int idx = PREFERRED_ORDER.indexOf(p.getType());
                    return idx < 0 ? Integer.MAX_VALUE : idx;
                }))
                .map(this::createMqGroup)
                .toList();

        root.getChildren().setAll(groups);
        return root;
    }

    /** 为一个 MQ 提供者构建可展开分组（含其 nav 子页面）。 */
    private NavTree.Item createMqGroup(MQUIProvider provider) {
        var group = NavTree.Item.group(provider.getDisplayName(), brandIcon(provider));
        group.setExpanded(true);

        var children = provider.getNavPages().stream()
                .map(page -> {
                    var item = NavTree.Item.page(
                            page.title(), null, provider.getType().toLowerCase() + ":" + page.pageId());
                    pages.add(item);
                    return item;
                })
                .toList();
        group.getChildren().setAll(children);
        return group;
    }

    /** 取得品牌图标并缩放为导航栏合适的尺寸。 */
    private static Node brandIcon(MQUIProvider provider) {
        Node icon = provider.getIcon();
        if (icon instanceof ImageView iv) {
            iv.setFitWidth(22);
            iv.setFitHeight(22);
        }
        return icon;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Header                                                                //
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
