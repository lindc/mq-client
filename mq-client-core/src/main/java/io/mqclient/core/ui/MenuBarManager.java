package io.mqclient.core.ui;

import io.mqclient.core.config.LocaleHolder;
import javafx.scene.control.*;
import org.springframework.context.MessageSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 菜单栏管理器。
 * <p>
 * 管理「固定公用菜单」和「MQ 注入的菜单」。
 * 固定菜单始终存在；MQ 注入菜单在切换连接时动态增删。
 * <p>
 * 所有菜单文字通过 {@link MessageSource} 按当前 {@link Locale} 解析，
 * 因此支持国际化；切换语言后调用 {@link #rebuild()} 即可实时刷新。
 */
public class MenuBarManager {

    private static final int INJECT_POSITION = 3; // File(0), Edit(1), Connection(2), [注入], View(3), Help(4)

    private final MenuBar menuBar;
    private final List<Menu> injectedMenus;
    private final Runnable onNewConnection;
    private final MessageSource messageSource;
    private final LocaleHolder localeHolder;

    public MenuBarManager(MenuBar menuBar, Runnable onNewConnection,
                          MessageSource messageSource, LocaleHolder localeHolder) {
        this.menuBar = menuBar;
        this.injectedMenus = new ArrayList<>();
        this.onNewConnection = onNewConnection;
        this.messageSource = messageSource;
        this.localeHolder = localeHolder;
        buildFixedMenus();
    }

    private String msg(String key) {
        return messageSource.getMessage(key, null, key, localeHolder.getLocale());
    }

    private void buildFixedMenus() {
        // File
        Menu fileMenu = new Menu(msg("menu.file"));
        MenuItem exitItem = new MenuItem(msg("menu.file.exit"));
        exitItem.setOnAction(e -> javafx.application.Platform.exit());
        fileMenu.getItems().add(exitItem);

        // Edit
        Menu editMenu = new Menu(msg("menu.edit"));
        editMenu.getItems().addAll(
                new MenuItem(msg("menu.edit.copy")),
                new MenuItem(msg("menu.edit.paste"))
        );

        // Connection（新建连接入口）
        Menu connMenu = new Menu(msg("menu.connection"));
        MenuItem newConnItem = new MenuItem(msg("menu.connection.new"));
        newConnItem.setOnAction(e -> onNewConnection.run());
        connMenu.getItems().addAll(
                newConnItem,
                new SeparatorMenuItem(),
                new MenuItem(msg("menu.connection.edit")),
                new MenuItem(msg("menu.connection.delete")),
                new SeparatorMenuItem(),
                new MenuItem(msg("menu.connection.import")),
                new MenuItem(msg("menu.connection.export"))
        );

        // View
        Menu viewMenu = new Menu(msg("menu.view"));

        // Help（关于 + 语言切换）
        Menu helpMenu = new Menu(msg("menu.help"));
        MenuItem aboutItem = new MenuItem(msg("menu.help.about"));
        aboutItem.setOnAction(e -> {
            try {
                new AboutDialog(messageSource, localeHolder).showAndWait();
            } catch (Throwable t) {
                t.printStackTrace();
                new Alert(Alert.AlertType.ERROR,
                        "打开关于对话框失败：\n" + t, ButtonType.OK).showAndWait();
            }
        });
        helpMenu.getItems().addAll(aboutItem, new SeparatorMenuItem());

        Menu langMenu = new Menu(msg("menu.language"));
        MenuItem zhItem = new MenuItem(msg("menu.language.zh"));
        MenuItem enItem = new MenuItem(msg("menu.language.en"));
        zhItem.setOnAction(e -> switchLocale(Locale.SIMPLIFIED_CHINESE));
        enItem.setOnAction(e -> switchLocale(Locale.ENGLISH));
        langMenu.getItems().addAll(zhItem, enItem);
        helpMenu.getItems().add(langMenu);

        menuBar.getMenus().addAll(fileMenu, editMenu, connMenu, viewMenu, helpMenu);
    }

    private void switchLocale(Locale locale) {
        localeHolder.setLocale(locale);
        rebuild();
    }

    /** 按当前 Locale 重建固定菜单（语言切换时调用）。 */
    public void rebuild() {
        menuBar.getMenus().clear();
        buildFixedMenus();
        injectMenus(injectedMenus);
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
