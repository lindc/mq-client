package org.bear.mqclient.core.spi;

import org.bear.mqclient.core.model.MQConnectionConfig;
import org.bear.mqclient.core.service.MQService;
import javafx.scene.Node;
import javafx.scene.control.Menu;

import java.util.List;

/**
 * MQ UI 提供者 SPI。
 * <p>
 * 每个 MQ 类型的 UI 模块实现此接口，并在
 * {@code META-INF/services/org.bear.mqclient.core.spi.MQUIProvider} 中注册。
 * <p>
 * core 在选中左侧连接时，调用此接口获取该 MQ 的完整面板和菜单。
 */
public interface MQUIProvider {

    /** 类型标识，与 MQServiceProvider.getType() 一致 */
    String getType();

    /** 显示名称 */
    String getDisplayName();

    /** 获取该 MQ 类型的图标（用于左侧导航） */
    default Node getIcon() { return null; }

    /**
     * 左侧导航中该 MQ 分组下的页面列表（连接 / 队列 / 主题等具体选项）。
     * <p>
     * 由各 MQ 的 UI 模块自行决定提供哪些页面；core 据此把该 MQ 渲染为
     * 一个可展开的分组，每个返回项成为一个可点击的叶子页面。
     */
    default List<MQNavPage> getNavPages() { return List.of(); }

    /**
     * 创建右侧主操作面板（完全由 MQ 客户端控制布局和内容）。
     * core 将此 Node 放入右侧内容区。
     */
    Node createMainPanel(MQConnectionConfig config, MQService service);

    /**
     * 创建连接配置编辑器面板。
     * core 将其嵌入「新建连接」/「编辑连接」对话框。
     * <p>
     * MQ 客户端自行渲染所有配置字段，提供「测试连接」功能。
     */
    Node createConfigEditor();

    /**
     * core 在显示配置编辑器后调用此方法，将待编辑的 config 注入给编辑器。
     * <p>
     * 默认实现通过 {@link Node#setUserData(Object)} 传递；
     * 各 MQ 编辑器可覆盖此方法自行处理绑定。
     */
    default void applyConfig(Node editor, MQConnectionConfig config) {
        editor.setUserData(config);
    }

    /**
     * 该 MQ 注入到主菜单栏的菜单项列表。
     * core 会在选中该 MQ 连接时注入这些菜单，取消选中时移除。
     */
    List<Menu> getMenus();

    /**
     * 「测试连接」动作 — core 将其绑定到新建连接对话框底部的「测试连接」按钮。
     * <p>
     * 插件返回 null 表示无测试动作（按钮无响应）；通常返回配置编辑器的
     * {@code testConnection()} 方法引用。
     */
    default Runnable getTestAction(Node editor) { return null; }

    /** 面板关闭/连接切换时清理回调 */
    void onPanelClosed();
}
