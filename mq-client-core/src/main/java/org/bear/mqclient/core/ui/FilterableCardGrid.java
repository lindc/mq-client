package org.bear.mqclient.core.ui;

import org.bear.mqclient.core.model.MQDestination;
import org.bear.mqclient.core.util.I18n;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 可过滤的卡片网格 — 顶部过滤栏（子串 / 正则切换）+ TilePane 多列卡片 + 滚动。
 * <p>
 * 用于 queue / topic 列表：卡片显示名字与关键属性，比单列表格信息密度高；
 * 配合过滤条件应对大数据量（TilePane 不虚拟化，靠过滤把可见结果控制在合理规模，
 * 超过 {@link #MAX_CARDS} 时给出提示）。
 */
public class FilterableCardGrid extends VBox {

    /** 可见卡片上限，超过时提示缩小过滤条件 */
    public static final int MAX_CARDS = 500;

    /** 卡片渲染工厂：插件可自定义卡片内容 */
    @FunctionalInterface
    public interface CardFactory {
        Node createCard(MQDestination destination);
    }

    /** 卡片点击回调（左键） */
    @FunctionalInterface
    public interface CardClickHandler {
        void onClick(MQDestination destination);
    }

    /** 卡片右键菜单工厂（返回 null 则不显示菜单） */
    @FunctionalInterface
    public interface CardMenuFactory {
        ContextMenu createMenu(MQDestination destination);
    }

    private final TextField filterBox = new TextField();
    private final ToggleButton regexToggle = new ToggleButton(I18n.get("common.regex"));
    private final Label tooManyLabel = new Label();
    private final TilePane grid = new TilePane();
    private final CardFactory cardFactory;
    private final CardClickHandler clickHandler;
    private final CardMenuFactory menuFactory;

    private List<MQDestination> all = List.of();

    /**
     * @param cardFactory  卡片渲染工厂；传 null 使用默认卡片（名字 + 类型徽章 + 消息数）
     * @param clickHandler 卡片左键点击回调；可为 null
     * @param menuFactory  卡片右键菜单工厂；可为 null
     */
    public FilterableCardGrid(CardFactory cardFactory, CardClickHandler clickHandler,
                              CardMenuFactory menuFactory) {
        this.cardFactory = cardFactory != null ? cardFactory : FilterableCardGrid::defaultCard;
        this.clickHandler = clickHandler;
        this.menuFactory = menuFactory;

        filterBox.setPromptText(I18n.get("common.filterPrompt"));
        HBox.setHgrow(filterBox, Priority.ALWAYS);
        HBox filterBar = new HBox(6, filterBox, regexToggle);

        tooManyLabel.setStyle("-fx-text-fill: #c0392b; -fx-padding: 4 0 0 2;");
        tooManyLabel.setVisible(false);

        grid.setPrefColumns(3);
        grid.setPrefTileWidth(200);
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().addAll(filterBar, tooManyLabel, scroll);

        // 过滤条件变化时实时刷新
        filterBox.textProperty().addListener((o, a, b) -> applyFilter());
        regexToggle.selectedProperty().addListener((o, a, b) -> applyFilter());
    }

    /** 设置全量目的地列表（过滤在视图层进行，不修改传入列表） */
    public void setDestinations(List<MQDestination> destinations) {
        this.all = destinations != null ? destinations : List.of();
        applyFilter();
    }

    // ── 过滤与渲染 ──

    private void applyFilter() {
        grid.getChildren().clear();

        String q = filterBox.getText();
        boolean regex = regexToggle.isSelected();

        Pattern pattern = null;
        if (regex && !q.isBlank()) {
            try {
                pattern = Pattern.compile(q, Pattern.CASE_INSENSITIVE);
                filterBox.setStyle("");
            } catch (PatternSyntaxException e) {
                // 非法正则：输入框标红，保持现有结果
                filterBox.setStyle("-fx-border-color: #e74c3c;");
                return;
            }
        }
        filterBox.setStyle("");

        int matched = 0;
        int shown = 0;
        for (MQDestination d : all) {
            boolean hit = q.isBlank() || (pattern != null
                    ? pattern.matcher(d.getName()).find()
                    : d.getName().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)));
            if (!hit) continue;
            matched++;
            if (shown >= MAX_CARDS) continue;
            shown++;
            grid.getChildren().add(buildCard(d));
        }

        if (matched > MAX_CARDS) {
            tooManyLabel.setText(I18n.get("common.filterTooMany", matched, MAX_CARDS));
            tooManyLabel.setVisible(true);
        } else {
            tooManyLabel.setVisible(false);
        }
    }

    private Node buildCard(MQDestination destination) {
        Node card = cardFactory.createCard(destination);
        if (clickHandler != null) {
            card.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    clickHandler.onClick(destination);
                }
            });
        }
        if (menuFactory != null) {
            ContextMenu menu = menuFactory.createMenu(destination);
            if (menu != null) {
                // Node 没有 setContextMenu（那是 Control 的方法），手动在右键请求时弹出
                card.setOnContextMenuRequested(e -> {
                    menu.show(card, e.getScreenX(), e.getScreenY());
                    e.consume();
                });
            }
        }
        return card;
    }

    // ── 默认卡片 ──

    private static final String CARD_STYLE =
            "-fx-background-color: white; -fx-background-radius: 8; -fx-border-radius: 8;"
                    + " -fx-border-color: #d0d0d0; -fx-border-width: 1; -fx-padding: 10;"
                    + " -fx-cursor: hand;";
    private static final String CARD_STYLE_HOVER =
            "-fx-background-color: #eef4fb; -fx-background-radius: 8; -fx-border-radius: 8;"
                    + " -fx-border-color: #7aa7d0; -fx-border-width: 1; -fx-padding: 10;"
                    + " -fx-cursor: hand;";

    /** 默认卡片：名字（粗体）+ 类型徽章 + 消息数 */
    public static Node defaultCard(MQDestination destination) {
        VBox card = new VBox(6);
        card.setStyle(CARD_STYLE);
        card.setOnMouseEntered(e -> card.setStyle(CARD_STYLE_HOVER));
        card.setOnMouseExited(e -> card.setStyle(CARD_STYLE));

        Label name = new Label(destination.getName());
        name.setWrapText(true);
        name.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        Label badge = new Label(destination.getType().name());
        badge.setStyle("-fx-font-size: 10px; -fx-text-fill: white;"
                + " -fx-background-color: #607d8b; -fx-background-radius: 6;"
                + " -fx-padding: 1 6 1 6;");

        HBox meta = new HBox(8, badge);
        meta.setAlignment(Pos.CENTER_LEFT);
        if (destination.getMessageCount() > 0) {
            Label count = new Label(NumberFormat.getIntegerInstance().format(destination.getMessageCount())
                    + " " + I18n.get("common.messages"));
            count.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
            meta.getChildren().add(count);
        }

        card.getChildren().addAll(name, meta);
        return card;
    }
}
