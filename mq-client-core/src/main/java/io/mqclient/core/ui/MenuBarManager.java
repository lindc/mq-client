package io.mqclient.core.ui;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.SeparatorMenuItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单栏管理器。
 * <p>
 * 管理「固定公用菜单」和「MQ 注入的菜单」。
 * 固定菜单始终存在；MQ 注入菜单在切换连接时动态增删。
 */
public class MenuBarManager {

    private static final int INJECT_POSITION = 3; // File(0), Edit(1), Connection(2), [注入], View(3), Help(4)

    private final MenuBar menuBar;
    private final List<Menu> injectedMenus;

    public MenuBarManager(MenuBar menuBar, Runnable onNewConnection) {
        this.menuBar = menuBar;
        this.injectedMenus = new ArrayList<>();
        buildFixedMenus(onNewConnection);
    }

    private void buildFixedMenus(Runnable onNewConnection) {
        // File
        Menu fileMenu = new Menu("File");
        MenuItem exitItem = new MenuItem("退出");
        exitItem.setOnAction(e -> javafx.application.Platform.exit());
        fileMenu.getItems().add(exitItem);

        // Edit
        Menu editMenu = new Menu("Edit");
        editMenu.getItems().addAll(
                new MenuItem("复制"),
                new MenuItem("粘贴")
        );

        // Connection（新建连接入口）
        Menu connMenu = new Menu("Connection");
        MenuItem newConnItem = new MenuItem("+ 新建连接");
        newConnItem.setOnAction(e -> onNewConnection.run());
        connMenu.getItems().addAll(
                newConnItem,
                new SeparatorMenuItem(),
                new MenuItem("编辑连接..."),
                new MenuItem("删除连接"),
                new SeparatorMenuItem(),
                new MenuItem("导入配置..."),
                new MenuItem("导出配置...")
        );

        // View
        Menu viewMenu = new Menu("View");

        // Help
        Menu helpMenu = new Menu("Help");
        MenuItem aboutItem = new MenuItem("关于");
        aboutItem.setOnAction(e -> {
            try {
                new AboutDialog().showAndWait();
            } catch (Throwable t) {
                t.printStackTrace();
                new Alert(Alert.AlertType.ERROR,
                        "打开关于对话框失败：\n" + t, ButtonType.OK).showAndWait();
            }
        });
        helpMenu.getItems().add(aboutItem);

        menuBar.getMenus().addAll(fileMenu, editMenu, connMenu, viewMenu, helpMenu);
    }

    /**
     * 注入 MQ 专属菜单（插入到 Connection 之后、View 之前）。
     */
    public void injectMenus(List<Menu> mqMenus) {
        clearInjectedMenus();
        if (mqMenus == null || mqMenus.isEmpty()) return;

        int pos = INJECT_POSITION;
        for (Menu menu : mqMenus) {
            menuBar.getMenus().add(pos++, menu);
            injectedMenus.add(menu);
        }
    }

    /**
     * 清除所有已注入的 MQ 菜单。
     */
    public void clearInjectedMenus() {
        menuBar.getMenus().removeAll(injectedMenus);
        injectedMenus.clear();
    }
}
