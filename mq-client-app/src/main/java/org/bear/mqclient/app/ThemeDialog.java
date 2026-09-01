package org.bear.mqclient.app;

import atlantafx.base.controls.Card;
import atlantafx.base.controls.ModalPane;
import atlantafx.base.controls.Tile;
import atlantafx.base.layout.ModalBox;
import atlantafx.base.theme.Theme;
import atlantafx.base.theme.Tweaks;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 主题选择对话框，仿 AtlantaFX Sampler 的 ThemeDialog：
 * 一个 3 列的缩略图网格，点选即切换主题。
 */
final class ThemeDialog extends ModalBox {

    private final TilePane thumbnailsPane = new TilePane(20, 20);
    private final ToggleGroup group = new ToggleGroup();

    ThemeDialog(List<Theme> themes, Theme current, Consumer<Theme> onSelect) {
        super("#" + App.MODAL_PANE_ID);

        setId("theme-dialog");
        getStyleClass().add("modal-dialog");

        var header = new Tile();
        header.setTitle("选择主题");

        thumbnailsPane.setAlignment(Pos.TOP_CENTER);
        thumbnailsPane.setPrefColumns(3);
        thumbnailsPane.setStyle("-color-thumbnail-border:-color-border-subtle;");

        for (var theme : themes) {
            var thumbnail = new ThemeThumbnail(theme);
            thumbnail.setToggleGroup(group);
            thumbnail.setUserData(theme);
            thumbnail.setSelected(Objects.equals(current.getName(), theme.getName()));
            thumbnailsPane.getChildren().add(thumbnail);
        }

        var body = new VBox(thumbnailsPane);
        body.setPadding(new Insets(20));

        var card = new Card();
        card.setHeader(header);
        card.getStyleClass().add(Tweaks.EDGE_TO_EDGE);
        card.setBody(body);
        card.setFooter(null);

        // 关键：让 ModalBox 按内容 pref 大小显示，而非撑满整个窗口
        setMinWidth(USE_PREF_SIZE);
        setMaxWidth(USE_PREF_SIZE);
        setMinHeight(USE_PREF_SIZE);
        setMaxHeight(USE_PREF_SIZE);

        setTopAnchor(card, 0d);
        setRightAnchor(card, 0d);
        setBottomAnchor(card, 0d);
        setLeftAnchor(card, 0d);

        addContent(card);

        group.selectedToggleProperty().addListener((obs, old, val) -> {
            if (val != null && val.getUserData() instanceof Theme theme) {
                onSelect.accept(theme);
            }
        });
    }

    void show(Scene scene) {
        var modalPane = (ModalPane) scene.lookup("#" + App.MODAL_PANE_ID);
        modalPane.show(this);
    }
}
