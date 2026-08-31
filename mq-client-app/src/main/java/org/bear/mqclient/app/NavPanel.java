package org.bear.mqclient.app;

import atlantafx.base.controls.CustomTextField;
import atlantafx.base.controls.Spacer;
import atlantafx.base.theme.Styles;
import atlantafx.base.theme.Tweaks;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 左侧 WinUI/Fluent 风格导航：图标 + 名称 + chevron + 状态点。
 */
class NavPanel {

    private static final double EXPANDED_WIDTH = 290;
    private static final double COLLAPSED_WIDTH = 36;
    private static final Map<String, Image> ICON_CACHE = new HashMap<>();

    record ObjectType(String connName, String type, List<DemoData.Dest> dests) {
    }

    private final List<DemoData.Conn> connections;
    private final Consumer<ObjectType> onOpenType;
    private final CustomTextField search = new CustomTextField();
    private final VBox listBox = new VBox(2);
    private final VBox expandedView = new VBox(8);
    private final VBox collapsedStrip = new VBox(6);
    private final StackPane container = new StackPane(expandedView, collapsedStrip);

    private Consumer<Boolean> onCollapsedChange;
    private boolean collapsed;

    // 选中的对象类型行，用于高亮当前打开的对象类型。
    private HBox selectedRow;

    private static final String ROW_BASE = "-fx-background-radius: 4; -fx-cursor: hand;";
    private static final String ROW_HOVER = "-fx-background-color: -color-bg-subtle;";
    private static final String ROW_SELECTED = "-fx-background-color: -color-accent-subtle;";

    NavPanel(List<DemoData.Conn> connections, Consumer<ObjectType> onOpenType) {
        this.connections = connections;
        this.onOpenType = onOpenType;

        search.setPromptText("搜索");
        search.setLeft(new Label("🔍"));
        search.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(search, Priority.ALWAYS);
        search.textProperty().addListener((obs, oldVal, newVal) -> rebuild());

        Button collapse = new Button("«");
        collapse.getStyleClass().add(Styles.BUTTON_ICON);
        collapse.setOnAction(e -> setCollapsed(true));

        listBox.setPadding(new Insets(4, 8, 4, 8));
        listBox.setFillWidth(true);
        VBox.setVgrow(listBox, Priority.ALWAYS);

        ScrollPane scroll = new ScrollPane(listBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add(Tweaks.EDGE_TO_EDGE);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        HBox headerRow = new HBox(6, search, collapse);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.setPadding(new Insets(0, 0, 4, 0));

        expandedView.getChildren().setAll(headerRow, scroll);
        expandedView.setPadding(new Insets(10, 10, 10, 10));
        expandedView.setPrefWidth(EXPANDED_WIDTH);
        expandedView.setMinWidth(EXPANDED_WIDTH);

        buildCollapsedStrip();
        container.setAlignment(Pos.TOP_LEFT);
        container.setPrefWidth(EXPANDED_WIDTH);

        rebuild();
        setCollapsed(false);
    }

    private void buildCollapsedStrip() {
        Button expand = new Button("»");
        expand.getStyleClass().add(Styles.BUTTON_ICON);
        expand.setOnAction(e -> setCollapsed(false));

        VBox verticalText = new VBox(2);
        verticalText.setAlignment(Pos.TOP_CENTER);
        for (char c : "连接".toCharArray()) {
            Label letter = new Label(String.valueOf(c));
            letter.getStyleClass().addAll(Styles.TEXT_CAPTION, Styles.TEXT_MUTED);
            verticalText.getChildren().add(letter);
        }
        collapsedStrip.getChildren().setAll(expand, verticalText);
        collapsedStrip.setAlignment(Pos.TOP_CENTER);
        collapsedStrip.setPadding(new Insets(8, 0, 8, 0));
        collapsedStrip.setPrefWidth(COLLAPSED_WIDTH);
        collapsedStrip.setMinWidth(COLLAPSED_WIDTH);
    }

    Node getView() {
        return container;
    }

    boolean isCollapsed() {
        return collapsed;
    }

    void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
        expandedView.setVisible(!collapsed);
        expandedView.setManaged(!collapsed);
        collapsedStrip.setVisible(collapsed);
        collapsedStrip.setManaged(collapsed);
        container.setPrefWidth(collapsed ? COLLAPSED_WIDTH : EXPANDED_WIDTH);
        container.setMinWidth(collapsed ? COLLAPSED_WIDTH : EXPANDED_WIDTH);
        if (onCollapsedChange != null) {
            onCollapsedChange.accept(collapsed);
        }
    }

    void setOnCollapsedChange(Consumer<Boolean> onCollapsedChange) {
        this.onCollapsedChange = onCollapsedChange;
    }

    private void rebuild() {
        String keyword = search.getText() == null ? "" : search.getText().trim().toLowerCase();
        listBox.getChildren().clear();
        connections.stream()
                .filter(c -> matches(c, keyword))
                .forEach(this::addConnection);
    }

    private void addConnection(DemoData.Conn conn) {
        VBox body = new VBox(2);
        body.setPadding(new Insets(2, 0, 4, 28));
        body.setVisible(false);
        body.setManaged(false);

        LinkedHashMap<String, List<DemoData.Dest>> byType = new LinkedHashMap<>();
        conn.dests().forEach(d -> byType.computeIfAbsent(d.type(), k -> new ArrayList<>()).add(d));
        byType.forEach((type, dests) -> body.getChildren().add(typeRow(conn, type, dests)));

        Label chevron = new Label("›");
        chevron.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);
        chevron.setMinWidth(14);

        HBox header = headerRow(conn, chevron);
        header.setMaxWidth(Double.MAX_VALUE);
        header.setOnMouseClicked(e -> {
            boolean expanded = !body.isVisible();
            body.setVisible(expanded);
            body.setManaged(expanded);
            chevron.setRotate(expanded ? 90 : 0);
        });

        applyRowFx(header, null);
        listBox.getChildren().addAll(header, body);
    }

    private HBox headerRow(DemoData.Conn conn, Label chevron) {
        Label name = new Label(conn.name());
        name.getStyleClass().add(Styles.TEXT_BOLD);
        name.setTextOverrun(OverrunStyle.ELLIPSIS);

        Label host = new Label(conn.host());
        host.getStyleClass().addAll(Styles.TEXT_CAPTION, Styles.TEXT_MUTED);

        VBox text = new VBox(1, name, host);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);

        Label dot = new Label("●");
        dot.setStyle(conn.connected()
                ? "-fx-text-fill: -color-success-fg;"
                : "-fx-text-fill: -color-fg-subtle;");
        dot.getStyleClass().add(Styles.TEXT_SMALL);

        HBox row = new HBox(10, icon(conn.type()), text, spacer(), chevron, dot);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(7, 10, 7, 10));
        row.setStyle(ROW_BASE);
        return row;
    }

    private HBox typeRow(DemoData.Conn conn, String type, List<DemoData.Dest> dests) {
        Label name = new Label(type);
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);
        name.setTextOverrun(OverrunStyle.ELLIPSIS);

        Label count = new Label(String.valueOf(dests.size()));
        count.getStyleClass().addAll(Styles.TEXT_CAPTION, Styles.TEXT_MUTED);
        count.setStyle("-fx-background-color: -color-bg-subtle;"
                + "-fx-background-radius: 8;"
                + "-fx-padding: 0 6 0 6;");

        HBox row = new HBox(8, name, count);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 10, 6, 10));
        row.setMaxWidth(Double.MAX_VALUE);
        row.setStyle(ROW_BASE);

        applyRowFx(row, () -> {
            selectRow(row);
            onOpenType.accept(new ObjectType(conn.name(), type, dests));
        });
        return row;
    }

    /** 给行添加悬停与点击效果；onClick 为空时仅支持悬停（连接表头）。 */
    private void applyRowFx(HBox row, Runnable onSelect) {
        row.hoverProperty().addListener((obs, wasHover, isHover) -> paintRow(row, isHover));
        if (onSelect != null) {
            row.setOnMouseClicked(e -> {
                if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                    onSelect.run();
                }
            });
        }
    }

    private void selectRow(HBox row) {
        HBox previous = selectedRow;
        selectedRow = row;
        if (previous != null) {
            paintRow(previous, previous.isHover());
        }
        paintRow(row, true);
    }

    private void paintRow(HBox row, boolean hover) {
        if (row == selectedRow) {
            row.setStyle(ROW_BASE + ROW_SELECTED);
        } else if (hover) {
            row.setStyle(ROW_BASE + ROW_HOVER);
        } else {
            row.setStyle(ROW_BASE);
        }
    }

    private static Node icon(String mqType) {
        Image image = loadIcon(mqType);
        if (image == null) {
            Label p = new Label("▪");
            p.getStyleClass().add(Styles.TEXT_SUBTLE);
            p.setMinWidth(18);
            return p;
        }
        ImageView v = new ImageView(image);
        v.setFitWidth(18);
        v.setFitHeight(18);
        v.setPreserveRatio(true);
        return v;
    }

    private static Image loadIcon(String mqType) {
        if (mqType == null) return null;
        String file = "/icons/" + mqType.toLowerCase().replace(' ', '-') + ".png";
        return ICON_CACHE.computeIfAbsent(file, f -> {
            try (InputStream in = NavPanel.class.getResourceAsStream(f)) {
                return in == null ? null : new Image(in);
            } catch (IOException e) {
                return null;
            }
        });
    }

    private static boolean matches(DemoData.Conn conn, String keyword) {
        if (keyword.isEmpty()) return true;
        return conn.name().toLowerCase().contains(keyword)
                || conn.host().toLowerCase().contains(keyword)
                || conn.dests().stream().anyMatch(d -> d.name().toLowerCase().contains(keyword));
    }

    private static Spacer spacer() {
        Spacer s = new Spacer();
        HBox.setHgrow(s, Priority.ALWAYS);
        return s;
    }
}
