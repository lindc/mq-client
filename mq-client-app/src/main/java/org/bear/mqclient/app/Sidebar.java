package org.bear.mqclient.app;

import atlantafx.base.controls.Spacer;
import atlantafx.base.theme.Styles;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.image.ImageView;
import javafx.scene.shape.Circle;
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
        var group = NavTree.Item.group(provider.getDisplayName(), createMqGroupGraphic(provider));
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

    /**
     * 构建 MQ 分组行的两行图形：左侧放大后的品牌图标，右侧第一行是 MQ 名称 + 状态圆点，
     * 第二行是连接信息（host:port，目前用假数据占位）。
     */
    private static Node createMqGroupGraphic(MQUIProvider provider) {
        var icon = brandIcon(provider, 30);

        var name = new Label(provider.getDisplayName());
        name.getStyleClass().add(Styles.TEXT_BOLD);

        var dot = new Circle(4);
        dot.setStyle("-fx-fill: -color-success-fg;");
        dot.getStyleClass().add("status-dot");
        dot.setManaged(true);

        var line1 = new HBox(6, name, dot);
        line1.setAlignment(Pos.CENTER_LEFT);

        var conn = new Label(fakeConnection(provider));
        conn.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_SMALL);

        var lines = new VBox(2, line1, conn);
        lines.setAlignment(Pos.CENTER_LEFT);

        var box = new HBox(10, icon, lines);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** 各 MQ 的假连接信息（host:port），后续接入真实连接后替换。 */
    private static String fakeConnection(MQUIProvider provider) {
        return switch (provider.getType()) {
            case "KAFKA" -> "localhost:9092";
            case "RABBITMQ" -> "localhost:5672";
            case "IBM_MQ" -> "localhost:1414";
            case "ARTEMIS" -> "localhost:61616";
            case "ROCKETMQ" -> "localhost:9876";
            default -> "localhost:0";
        };
    }

    /** 取得品牌图标并缩放为指定尺寸。 */
    private static Node brandIcon(MQUIProvider provider, double size) {
        Node icon = provider.getIcon();
        if (icon instanceof ImageView iv) {
            iv.setFitWidth(size);
            iv.setFitHeight(size);
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
