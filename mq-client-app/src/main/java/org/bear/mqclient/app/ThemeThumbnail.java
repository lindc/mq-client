package org.bear.mqclient.app;

import atlantafx.base.theme.Styles;
import atlantafx.base.theme.Theme;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 主题缩略图（方格瓦片）：展示主题名 + 一排配色圆点，点击即选中。
 * 配色从主题自身的 user-agent stylesheet 里按 demo 同款正则解析得到。
 */
final class ThemeThumbnail extends VBox implements Toggle {

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private static final Pattern COLOR_PATTERN =
            Pattern.compile("\\s*?(-color-(fg|bg|accent|success|danger|warning)-.+?):\\s*?(.+?);");

    private final RadioButton toggle = new RadioButton(); // 仅用于承载选中态

    ThemeThumbnail(Theme theme) {
        try {
            Map<String, String> colors = parseColors(theme);

            var circles = new HBox(
                    circle(colors.get("-color-fg-default"), colors.get("-color-fg-default"), false),
                    circle(colors.get("-color-fg-default"), colors.get("-color-accent-emphasis"), true),
                    circle(colors.get("-color-fg-default"), colors.get("-color-success-emphasis"), true),
                    circle(colors.get("-color-fg-default"), colors.get("-color-danger-emphasis"), true),
                    circle(colors.get("-color-fg-default"), colors.get("-color-warning-emphasis"), true)
            );
            circles.setAlignment(Pos.CENTER);

            var nameLbl = new Label(theme.getName());
            nameLbl.getStyleClass().add(Styles.TEXT_CAPTION);
            Styles.appendStyle(nameLbl, "-fx-text-fill", strOr(colors.get("-color-fg-muted"), "#808080"));

            setStyle("-fx-background-radius: 10px, 8px;\n-fx-background-insets: 0, 3px");
            Styles.appendStyle(this, "-fx-background-color",
                    "-color-thumbnail-border," + strOr(colors.get("-color-bg-default"), "#ffffff"));
            setOnMouseClicked(e -> setSelected(true));
            getStyleClass().add("theme-thumbnail");
            getChildren().setAll(nameLbl, circles);

        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }

        selectedProperty().addListener((obs, old, val) -> pseudoClassStateChanged(SELECTED, val));
    }

    private static Circle circle(String borderColor, String bgColor, boolean overlap) {
        var circle = new Circle(10);
        Styles.appendStyle(circle, "-fx-stroke", strOr(borderColor, "#808080"));
        Styles.appendStyle(circle, "-fx-fill", strOr(bgColor, "#808080"));
        if (overlap) {
            HBox.setMargin(circle, new Insets(0, 0, 0, -5));
        }
        return circle;
    }

    private static String strOr(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v.trim();
    }

    private static Map<String, String> parseColors(Theme theme) throws IOException {
        var colors = new HashMap<String, String>();
        // getUserAgentStylesheet() 返回的是 classpath 资源路径（如 /atlantafx/base/theme/primer-light.css），
        // 不是合法 URL，需按 classpath 资源读取。
        var path = theme.getUserAgentStylesheet();
        try (var br = new BufferedReader(new InputStreamReader(
                ThemeThumbnail.class.getResourceAsStream(path), StandardCharsets.UTF_8))) {
            String line;
            int n = 0;
            while ((line = br.readLine()) != null) {
                Matcher m = COLOR_PATTERN.matcher(line);
                if (m.matches()) {
                    colors.put(m.group(1), m.group(3));
                }
                if (++n > 250) {
                    break;
                }
            }
        }
        return colors;
    }

    @Override
    public ToggleGroup getToggleGroup() {
        return toggle.getToggleGroup();
    }

    @Override
    public void setToggleGroup(ToggleGroup toggleGroup) {
        toggle.setToggleGroup(toggleGroup);
    }

    @Override
    public ObjectProperty<ToggleGroup> toggleGroupProperty() {
        return toggle.toggleGroupProperty();
    }

    @Override
    public boolean isSelected() {
        return toggle.isSelected();
    }

    @Override
    public void setSelected(boolean selected) {
        toggle.setSelected(selected);
    }

    @Override
    public BooleanProperty selectedProperty() {
        return toggle.selectedProperty();
    }

    @Override
    public void setUserData(Object value) {
        toggle.setUserData(value);
    }

    @Override
    public Object getUserData() {
        return toggle.getUserData();
    }
}
