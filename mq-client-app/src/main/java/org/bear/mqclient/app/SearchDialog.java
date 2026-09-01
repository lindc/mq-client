package org.bear.mqclient.app;

import atlantafx.base.controls.Card;
import atlantafx.base.controls.CustomTextField;
import atlantafx.base.controls.ModalPane;
import atlantafx.base.controls.Spacer;
import atlantafx.base.controls.Tile;
import atlantafx.base.layout.ModalBox;
import atlantafx.base.theme.Styles;
import atlantafx.base.theme.Tweaks;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;

import java.util.List;
import java.util.function.Consumer;

/** 导航搜索弹窗，由侧边栏的搜索按钮打开。 */
final class SearchDialog extends ModalBox {

    private final CustomTextField searchField = new CustomTextField();
    private final ListView<NavTree.Item> resultList = new ListView<>();
    private final List<NavTree.Item> pages;
    private final Consumer<NavTree.Item> onSelect;

    SearchDialog(List<NavTree.Item> pages, Consumer<NavTree.Item> onSelect) {
        super("#" + App.MODAL_PANE_ID);

        this.pages = pages;
        this.onSelect = onSelect;

        setId("search-dialog");
        getStyleClass().add("modal-dialog");

        createView();
        init();
    }

    private void createView() {
        var placeholder = new Label("输入关键字后显示匹配的功能");
        placeholder.getStyleClass().add(Styles.TITLE_4);

        searchField.setLeft(new FontIcon(Material2MZ.SEARCH));
        VBox.setVgrow(searchField, Priority.NEVER);

        resultList.setPlaceholder(placeholder);
        resultList.getStyleClass().add(Tweaks.EDGE_TO_EDGE);
        resultList.setCellFactory(c -> new ResultListCell());
        VBox.setVgrow(resultList, Priority.ALWAYS);

        var body = new VBox(10, searchField, resultList);

        var header = new Tile();
        header.setTitle("搜索");

        var card = new Card();
        card.setHeader(header);
        card.setBody(body);
        card.setFooter(createFooter());
        card.getStyleClass().add(Tweaks.EDGE_TO_EDGE);
        card.setPrefSize(520, 360);

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
    }

    private HBox createFooter() {
        var closeBtn = new Button("关闭");
        closeBtn.getStyleClass().add("form-action");
        closeBtn.setCancelButton(true);
        closeBtn.setOnAction(e -> close());

        var footer = new HBox(10, new Spacer(), closeBtn);
        footer.getStyleClass().add("footer");
        footer.setAlignment(Pos.CENTER_RIGHT);
        return footer;
    }

    private void init() {
        searchField.textProperty().addListener((obs, old, val) -> {
            if (val == null || val.isBlank()) {
                resultList.getItems().clear();
                return;
            }
            resultList.getItems().setAll(
                    pages.stream().filter(item -> item.getValue().matches(val)).toList()
            );
        });

        searchField.addEventFilter(KeyEvent.KEY_RELEASED, e -> {
            if (e.getCode() == KeyCode.DOWN && !resultList.getItems().isEmpty()) {
                resultList.getSelectionModel().selectFirst();
                resultList.requestFocus();
            }
        });

        resultList.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && !resultList.getSelectionModel().isEmpty()) {
                navigate(resultList.getSelectionModel().getSelectedItem());
            }
        });

        resultList.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY
                    && e.getClickCount() == 2
                    && !resultList.getSelectionModel().isEmpty()) {
                navigate(resultList.getSelectionModel().getSelectedItem());
            }
        });
    }

    private void navigate(NavTree.Item item) {
        close();
        onSelect.accept(item);
    }

    void show(Scene scene) {
        var modalPane = (ModalPane) scene.lookup("#" + App.MODAL_PANE_ID);
        modalPane.show(this);
        Platform.runLater(searchField::requestFocus);
    }

    ///////////////////////////////////////////////////////////////////////////

    private static final class ResultListCell extends ListCell<NavTree.Item> {

        private final HBox root;
        private final Label parentLabel;
        private final Label targetLabel;

        ResultListCell() {
            parentLabel = new Label();
            parentLabel.getStyleClass().add(Styles.TEXT_MUTED);

            var separator = new FontIcon(Material2AL.CHEVRON_RIGHT);
            separator.getStyleClass().add("icon-subtle");

            var returnIcon = new FontIcon(Material2AL.KEYBOARD_RETURN);
            returnIcon.getStyleClass().add("icon-subtle");

            targetLabel = new Label();
            targetLabel.getStyleClass().add(Styles.TEXT_BOLD);

            root = new HBox(parentLabel, separator, targetLabel, new Spacer(), returnIcon);
            root.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(NavTree.Item item, boolean empty) {
            super.updateItem(item, empty);

            if (item == null || empty) {
                setGraphic(null);
            } else {
                parentLabel.setText(item.getParent().getValue().title());
                targetLabel.setText(item.getValue().title());
                setGraphic(root);
            }
        }
    }
}
