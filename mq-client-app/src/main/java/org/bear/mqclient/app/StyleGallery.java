package org.bear.mqclient.app;

import atlantafx.base.controls.Breadcrumbs;
import atlantafx.base.controls.Card;
import atlantafx.base.controls.CustomTextField;
import atlantafx.base.controls.MaskTextField;
import atlantafx.base.controls.Message;
import atlantafx.base.controls.Notification;
import atlantafx.base.controls.PasswordTextField;
import atlantafx.base.controls.Popover;
import atlantafx.base.controls.RingProgressIndicator;
import atlantafx.base.controls.SegmentedControl;
import atlantafx.base.controls.Tab;
import atlantafx.base.controls.TabLine;
import atlantafx.base.controls.Tile;
import atlantafx.base.controls.ToggleSwitch;
import atlantafx.base.theme.Styles;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.function.Function;

/**
 * AtlantaFX 样式总览：把 atlantafx-base 2.1.0 CSS 里真实存在的样式类按组铺开，
 * 每个样例下方标注类名，便于对照"类名 → 效果"。
 */
final class StyleGallery {

    static Node create() {
        Label hint = new Label("下列类名均取自 atlantafx-base 2.1.0 的 primer-light.css，可直接用于任意 Node 的 getStyleClass()");
        hint.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);

        VBox content = new VBox(24,
                hint,
                section("排版", typography()),
                section("文本语义色", textColors()),
                section("按钮", buttons()),
                section("输入控件", inputs()),
                section("背景 / 边框 / 阴影", surfaces()),
                section("数据展示", dataDisplay()),
                section("TabLine 变体", tabLines())
        );
        content.setPadding(new Insets(16));

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        return scroll;
    }

    // ---------- 排版 ----------

    private static Node typography() {
        return flow(
                sample("title-1", styled("标题一 Title 1", Styles.TITLE_1)),
                sample("title-2", styled("标题二 Title 2", Styles.TITLE_2)),
                sample("title-3", styled("标题三 Title 3", Styles.TITLE_3)),
                sample("title-4", styled("标题四 Title 4", Styles.TITLE_4)),
                sample("text-caption", styled("Caption 说明文字", Styles.TEXT_CAPTION)),
                sample("text-small", styled("Small 小字", Styles.TEXT_SMALL)),
                sample("text-normal", styled("Normal 常规", Styles.TEXT_NORMAL)),
                sample("text-bold", styled("Bold 加粗", Styles.TEXT_BOLD)),
                sample("text-bolder", styled("Bolder 更粗", Styles.TEXT_BOLDER)),
                sample("text-lighter", styled("Lighter 更细", Styles.TEXT_LIGHTER)),
                sample("text-italic", styled("Italic 斜体", Styles.TEXT_ITALIC)),
                sample("text-oblique", styled("Oblique 倾斜", Styles.TEXT_OBLIQUE)),
                sample("text-strikethrough", styled("Strikethrough 删除线", Styles.TEXT_STRIKETHROUGH)),
                sample("text-underlined", styled("Underlined 下划线", Styles.TEXT_UNDERLINED)),
                sample("text-muted", styled("Muted 弱化", Styles.TEXT_MUTED)),
                sample("text-subtle", styled("Subtle 更弱化", Styles.TEXT_SUBTLE))
        );
    }

    private static Node textColors() {
        StackPane onEmphasis = new StackPane(styled("text-on-emphasis", Styles.TEXT_ON_EMPHASIS));
        onEmphasis.getStyleClass().add(Styles.BG_ACCENT_EMPHASIS);
        onEmphasis.setPadding(new Insets(8));

        return flow(
                sample("accent", styled("强调色文本", Styles.ACCENT)),
                sample("success", styled("成功色文本", Styles.SUCCESS)),
                sample("warning", styled("警告色文本", Styles.WARNING)),
                sample("danger", styled("危险色文本", Styles.DANGER)),
                sample("bg-accent-emphasis", onEmphasis)
        );
    }

    // ---------- 按钮 ----------

    private static Node buttons() {
        Button iconBtn = new Button("⚙");
        iconBtn.getStyleClass().add(Styles.BUTTON_ICON);

        Button circleBtn = new Button("＋");
        circleBtn.getStyleClass().add(Styles.BUTTON_CIRCLE);

        Button pillLeft = new Button("左");
        pillLeft.getStyleClass().add(Styles.LEFT_PILL);
        Button pillCenter = new Button("中");
        pillCenter.getStyleClass().add(Styles.CENTER_PILL);
        Button pillRight = new Button("右");
        pillRight.getStyleClass().add(Styles.RIGHT_PILL);
        HBox pills = new HBox(pillLeft, pillCenter, pillRight);

        return flow(
                sample("（默认）", new Button("按钮")),
                sample("accent", styled(new Button("按钮"), Styles.ACCENT)),
                sample("success", styled(new Button("按钮"), Styles.SUCCESS)),
                sample("warning", styled(new Button("按钮"), Styles.WARNING)),
                sample("danger", styled(new Button("按钮"), Styles.DANGER)),
                sample("flat", styled(new Button("按钮"), Styles.FLAT)),
                sample("bordered", styled(new Button("按钮"), Styles.BORDERED)),
                sample("button-outlined", styled(new Button("按钮"), Styles.BUTTON_OUTLINED)),
                sample("button-icon", iconBtn),
                sample("button-circle", circleBtn),
                sample("small", styled(new Button("按钮"), Styles.SMALL)),
                sample("medium", styled(new Button("按钮"), Styles.MEDIUM)),
                sample("large", styled(new Button("按钮"), Styles.LARGE)),
                sample("left/center/right-pill", pills)
        );
    }

    // ---------- 输入控件 ----------

    private static Node inputs() {
        CustomTextField custom = new CustomTextField();
        custom.setPromptText("CustomTextField");
        custom.setLeft(new Label("🔍"));
        custom.setRight(new Label("⌫"));

        TextArea area = new TextArea("TextArea");
        area.setPrefRowCount(2);

        return flow(
                sample("TextField", new TextField("TextField")),
                sample("PasswordTextField", new PasswordTextField()),
                sample("MaskTextField", new MaskTextField("(999) 999-9999")),
                sample("CustomTextField (left/right)", custom),
                sample("TextArea", area),
                sample("ComboBox", new ComboBox<>(FXCollections.observableArrayList("选项一", "选项二"))),
                sample("Spinner", new Spinner<>(1, 10, 3)),
                sample("Slider", new Slider(0, 100, 40)),
                sample("ToggleSwitch", new ToggleSwitch("开关")),
                sample("CheckBox", new CheckBox("复选框")),
                sample("RadioButton", new RadioButton("单选框")),
                sample("ProgressBar", new ProgressBar(0.6)),
                sample("RingProgressIndicator", new RingProgressIndicator(0.65)),
                sample("SegmentedControl", new SegmentedControl("日", "周", "月"))
        );
    }

    // ---------- 背景 / 边框 / 阴影 ----------

    private static Node surfaces() {
        return flow(
                swatch(Styles.BG_DEFAULT),
                swatch(Styles.BG_INSET),
                swatch(Styles.BG_SUBTLE),
                swatch(Styles.BG_NEUTRAL_SUBTLE),
                swatch(Styles.BG_NEUTRAL_MUTED),
                swatch(Styles.BG_NEUTRAL_EMPHASIS),
                swatch(Styles.BG_NEUTRAL_EMPHASIS_PLUS),
                swatch(Styles.BG_ACCENT_SUBTLE),
                swatch(Styles.BG_ACCENT_MUTED),
                swatch(Styles.BG_ACCENT_EMPHASIS),
                swatch(Styles.BG_SUCCESS_SUBTLE),
                swatch(Styles.BG_SUCCESS_MUTED),
                swatch(Styles.BG_SUCCESS_EMPHASIS),
                swatch(Styles.BG_WARNING_SUBTLE),
                swatch(Styles.BG_WARNING_MUTED),
                swatch(Styles.BG_WARNING_EMPHASIS),
                swatch(Styles.BG_DANGER_SUBTLE),
                swatch(Styles.BG_DANGER_MUTED),
                swatch(Styles.BG_DANGER_EMPHASIS),
                swatch(Styles.BORDER_SUBTLE),
                swatch(Styles.BORDER_MUTED),
                swatch(Styles.BORDER_DEFAULT),
                swatch(Styles.ELEVATED_1),
                swatch(Styles.ELEVATED_2),
                swatch(Styles.ELEVATED_3),
                swatch(Styles.ELEVATED_4),
                swatch(Styles.ROUNDED),
                swatch(Styles.INTERACTIVE)
        );
    }

    // ---------- 数据展示 ----------

    private static Node dataDisplay() {
        TableView<DemoData.Msg> striped = messageTable();
        striped.getStyleClass().add(Styles.STRIPED);
        striped.setMaxHeight(120);

        TableView<DemoData.Msg> dense = messageTable();
        dense.getStyleClass().add(Styles.DENSE);
        dense.setMaxHeight(120);

        Card card = new Card();
        card.setHeader(styled("Card 标题", Styles.TEXT_BOLD));
        card.setBody(new Label("Card 正文内容，header / sub-header / body / footer 均可为任意 Node"));
        card.setPrefWidth(280);

        Tile tile = new Tile("Tile 标题", "Tile 描述文本");

        Notification notification = new Notification("这是一条 Notification 通知");
        notification.getPrimaryActions().add(new Button("知道了"));
        notification.setMaxWidth(320);

        Breadcrumbs<String> crumbs = new Breadcrumbs<>(Breadcrumbs.buildTreeModel("根", "分组", "队列"));

        Button popoverBtn = new Button("显示 Popover");
        Popover popover = new Popover(new Label("Popover 内容"));
        popoverBtn.setOnAction(e -> popover.show(popoverBtn));

        return flow(
                sample("Message · accent", styled(new Message("标题", "描述文本"), Styles.ACCENT)),
                sample("Message · success", styled(new Message("标题", "描述文本"), Styles.SUCCESS)),
                sample("Message · warning", styled(new Message("标题", "描述文本"), Styles.WARNING)),
                sample("Message · danger", styled(new Message("标题", "描述文本"), Styles.DANGER)),
                sample("TableView · striped", striped),
                sample("TableView · dense", dense),
                sample("Card", card),
                sample("Tile", tile),
                sample("Notification", notification),
                sample("Breadcrumbs", crumbs),
                sample("Popover", popoverBtn)
        );
    }

    private static TableView<DemoData.Msg> messageTable() {
        TableView<DemoData.Msg> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.getColumns().setAll(
                column("ID", DemoData.Msg::id, 110),
                column("时间", DemoData.Msg::time, 80),
                column("大小", DemoData.Msg::size, 70)
        );
        table.getItems().setAll(DemoData.MESSAGES);
        return table;
    }

    private static TableColumn<DemoData.Msg, String> column(String title,
                                                            Function<DemoData.Msg, String> getter,
                                                            double width) {
        TableColumn<DemoData.Msg, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(getter.apply(c.getValue())));
        return col;
    }

    // ---------- TabLine 变体 ----------

    private static Node tabLines() {
        return flow(
                sample("（默认）", tabLine(null)),
                sample("floating", tabLine(Styles.TABS_FLOATING)),
                sample("classic", tabLine(Styles.TABS_CLASSIC)),
                sample("border-top", tabLine(Styles.TABS_BORDER_TOP))
        );
    }

    private static TabLine tabLine(String styleClass) {
        TabLine tabs = new TabLine(new Tab("队列"), new Tab("主题"), new Tab("交换机"));
        tabs.setPrefWidth(300);
        if (styleClass != null) {
            tabs.getStyleClass().add(styleClass);
        }
        return tabs;
    }

    // ---------- 通用工具 ----------

    private static Node section(String title, Node content) {
        Label label = new Label(title);
        label.getStyleClass().add(Styles.TITLE_4);
        return new VBox(10, label, content);
    }

    private static FlowPane flow(Node... nodes) {
        FlowPane pane = new FlowPane(16, 12);
        pane.getChildren().setAll(nodes);
        pane.setAlignment(Pos.TOP_LEFT);
        return pane;
    }

    private static Node sample(String className, Node node) {
        Label name = new Label(className);
        name.getStyleClass().addAll(Styles.TEXT_CAPTION, Styles.TEXT_MUTED);
        VBox box = new VBox(4, node, name);
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }

    private static Node swatch(String className) {
        Label text = new Label(className);
        text.getStyleClass().addAll(Styles.TEXT_CAPTION);
        StackPane box = new StackPane(text);
        box.getStyleClass().add(className);
        box.setPadding(new Insets(8));
        box.setMinSize(130, 36);
        return sample(className, box);
    }

    private static <T extends Node> T styled(T node, String... styleClasses) {
        node.getStyleClass().addAll(styleClasses);
        return node;
    }

    private static Label styled(String text, String... styleClasses) {
        return styled(new Label(text), styleClasses);
    }

    private StyleGallery() {
    }
}
