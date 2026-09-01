package org.bear.mqclient.app;

import atlantafx.base.controls.Spacer;
import atlantafx.base.theme.Tweaks;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.Objects;
import java.util.function.Consumer;

/** 左侧导航树，结构与样式仿 AtlantaFX Sampler。 */
final class NavTree extends TreeView<Nav> {

    NavTree(Consumer<NavTree.Item> onSelect) {
        getSelectionModel().selectedItemProperty().addListener((obs, old, val) -> {
            if (val instanceof Item item && !item.isGroup()) {
                onSelect.accept(item);
            }
        });

        getStyleClass().add(Tweaks.EDGE_TO_EDGE);
        setShowRoot(false);
        setCellFactory(p -> new NavTreeCell());
    }

    ///////////////////////////////////////////////////////////////////////////

    static final class NavTreeCell extends TreeCell<Nav> {

        private static final PseudoClass GROUP = PseudoClass.getPseudoClass("group");

        private final HBox root;
        private final Label titleLabel;
        private final Node arrowIcon;

        NavTreeCell() {
            titleLabel = new Label();
            titleLabel.setGraphicTextGap(10);
            titleLabel.getStyleClass().add("title");

            // 图标码由 app.css 按展开/折叠态给出
            arrowIcon = new FontIcon();
            arrowIcon.getStyleClass().add("arrow");

            root = new HBox(titleLabel, new Spacer(), arrowIcon);
            root.setAlignment(Pos.CENTER_LEFT);
            root.setCursor(Cursor.HAND);
            root.getStyleClass().add("container");
            root.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(titleLabel, Priority.ALWAYS);

            root.setOnMouseClicked(e -> {
                if (getTreeItem() instanceof Item item
                        && item.isGroup()
                        && e.getButton() == MouseButton.PRIMARY) {
                    item.setExpanded(!item.isExpanded());
                    // 往上多滚一点，让分组下新展开的内容可见
                    getTreeView().scrollTo(Math.max(0, getTreeView().getRow(item) - 10));
                }
            });

            getStyleClass().add("nav-tree-cell");
        }

        @Override
        protected void updateItem(Nav nav, boolean empty) {
            super.updateItem(nav, empty);

            if (nav == null || empty) {
                setGraphic(null);
                titleLabel.setText(null);
                titleLabel.setGraphic(null);
            } else {
                setGraphic(root);
                // 分组行使用两行复合图形（品牌图标 + 名称/状态 + 连接信息），
                // 名称已在图形内，这里不再重复设置文字。
                titleLabel.setText(nav.isGroup() ? null : nav.title());
                titleLabel.setGraphic(nav.graphic());
                pseudoClassStateChanged(GROUP, nav.isGroup());
                toggleVisibility(arrowIcon, nav.isGroup());
            }
        }

        private static void toggleVisibility(Node node, boolean visible) {
            node.setVisible(visible);
            node.setManaged(visible);
        }
    }

    static final class Item extends TreeItem<Nav> {

        private final Nav nav;

        private Item(Nav nav) {
            this.nav = Objects.requireNonNull(nav, "nav");
            setValue(nav);
        }

        boolean isGroup() {
            return nav.isGroup();
        }

        static Item root() {
            return new Item(Nav.ROOT);
        }

        static Item group(String title, Node graphic) {
            return new Item(new Nav(title, graphic, null));
        }

        static Item page(String title, String pageId) {
            return page(title, null, pageId);
        }

        static Item page(String title, Node graphic, String pageId) {
            return new Item(new Nav(title, graphic, pageId));
        }
    }
}
