package com.mqbrowser.ui;

import com.mqbrowser.MQBrowserApp;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.util.Duration;
import org.controlsfx.control.StatusBar;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 主界面布局：
 * <pre>
 *   +------------------------------------------+
 *   | 菜单栏 MenuBar                            |
 *   +-------------+----------------------------+
 *   | 左侧面板     |  右侧面板                   |
 *   | (列表 +      |  (内层面板 + 多标签页表格,  |
 *   |  右键菜单)   |   行右键菜单)               |
 *   +-------------+----------------------------+
 *   | 状态栏 StatusBar（ControlsFX，右侧显示内存）|
 *   +------------------------------------------+
 * </pre>
 */
public class MainView {

    private final BorderPane root = new BorderPane();

    public MainView() {
        // 顶部：菜单栏
        root.setTop(buildMenuBar());

        // 左右面板用 SplitPane 排列，可拖动分隔条调整左侧宽度。
        // 左侧面板内部的卡片自带阴影（见 LeftPanel / app.css 的 .nav-card）。
        Region left = new LeftPanel().getNode();
        Region right = new RightPanel().getNode();

        // 中部：左 + 右 面板，可拖动调宽，默认左侧占 20%
        SplitPane content = new SplitPane(left, right);
        content.setDividerPositions(0.2);
        // 窗口缩放时优先拉伸右侧，左侧宽度保持不变
        SplitPane.setResizableWithParent(left, false);
        root.setCenter(content);

        // 底部：状态栏
        StatusBar statusBar = new StatusBar();
        statusBar.setText("Ready");

        // 在状态栏最右侧显示实时内存使用量
        Label memoryLabel = new Label();
        memoryLabel.setPadding(new Insets(0, 8, 0, 0));
        statusBar.getRightItems().add(memoryLabel);
        startMemoryMonitor(memoryLabel);

        root.setBottom(statusBar);
    }

    /**
     * 用当前堆内存使用情况（已用 / 已分配 / 最大）更新 {@code label}，每 2 秒刷新一次。
     * 右键点击该标签会触发一次 GC 并立即刷新。
     */
    private void startMemoryMonitor(Label label) {
        // 读取内存并更新文本的逻辑，供定时刷新和右键 GC 复用
        Runnable update = () -> {
            Runtime rt = Runtime.getRuntime();
            long mb = 1024 * 1024;
            long usedMb = (rt.totalMemory() - rt.freeMemory()) / mb;  // 已用 = 已分配 - 空闲
            long totalMb = rt.totalMemory() / mb;                     // JVM 当前已分配的堆
            long maxMb = rt.maxMemory() / mb;                         // JVM 可用的最大堆
            label.setText("Memory: " + usedMb + " / " + totalMb + " / " + maxMb + " MB");
        };
        update.run(); // 启动时先立即显示一个值，不必等 2 秒

        // 右键触发垃圾回收，然后立即刷新（可直观看到已用内存回落）
        label.setOnContextMenuRequested(e -> {
            System.gc();
            update.run();
        });

        // 定时器：每 2 秒刷新一次，无限循环。Timeline 在 JavaFX 线程上执行，线程安全。
        Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(2), e -> update.run()));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
    }

    /** 构建顶部菜单栏：File / Edit / Help。 */
    private MenuBar buildMenuBar() {
        Menu file = new Menu("File");
        MenuItem newItem = new MenuItem("New Connection");
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(e -> Platform.exit());   // 退出应用
        file.getItems().addAll(newItem, new SeparatorMenuItem(), exit);

        Menu edit = new Menu("Edit");
        edit.getItems().addAll(new MenuItem("Copy"), new MenuItem("Paste"));

        Menu help = new Menu("Help");
        MenuItem about = new MenuItem("About");
        about.setOnAction(e -> showAboutDialog());   // 弹出关于对话框
        help.getItems().add(about);

        return new MenuBar(file, edit, help);
    }

    /**
     * 显示"关于"对话框。
     * 内容区左侧是一个模拟三针时钟（秒针实时走动），右侧是一列冒烟的 ASCII 小火车。
     * 火车图案和烟雾帧从资源文件读取（见 resources/ascii/），便于修改而无需改代码。
     */
    private void showAboutDialog() {
        // 左侧：传统模拟三针时钟（秒针实时走动）
        AnalogClock clock = new AnalogClock(180);

        // 右侧：冒烟蒸汽火车（ASCII art）。
        // 车头图案固定，烟雾由多帧循环切换形成飘动动画，图案均来自资源文件。
        String engine = loadResourceText("/ascii/train.txt");
        // 烟雾各帧之间用一个空行分隔（即连续两个及以上换行）
        String[] smokeFrames = loadResourceText("/ascii/smoke.txt").split("\\R{2,}");
        // 规范化每帧：统一成相同的行数和列宽，帧间尺寸完全一致，画面不会跳动
        normalizeFrames(smokeFrames);

        // 用 Label 显示（等宽字体）。因为每帧已规范化为相同尺寸，Label 不会随内容重排。
        Label text = new Label(smokeFrames[0] + "\n" + engine);
        text.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace;"
                + " -fx-font-size: 11px;");
        text.setAlignment(Pos.TOP_LEFT);

        // 烟雾动画：每 300ms 切换一帧，循环播放
        final int[] frame = {0};
        Timeline smoke = new Timeline(new KeyFrame(Duration.millis(300), e -> {
            frame[0] = (frame[0] + 1) % smokeFrames.length;
            text.setText(smokeFrames[frame[0]] + "\n" + engine);
        }));
        smoke.setCycleCount(Animation.INDEFINITE);
        smoke.play();

        // 水平布局：左时钟 + 右文字，顶部对齐（不居中，避免高度变化导致上下移动）
        HBox content = new HBox(20, clock, text);
        content.setAlignment(Pos.TOP_LEFT);
        content.setPadding(new Insets(15));

        // 用 Dialog 承载自定义内容（Alert 只能放纯文本，故改用 Dialog）
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("About");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(500, 300);   // 容纳时钟 + 火车
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();

        // 对话框关闭后停止烟雾动画，避免 Timeline 一直在后台运行
        smoke.stop();
    }

    /**
     * 将烟雾各帧规范化：统一行数（补空行到最大行数），并把每行右侧用空格补齐到相同宽度。
     * 这样每帧渲染出的尺寸完全一致，动画切换时画面不会上下或左右跳动。
     */
    private void normalizeFrames(String[] frames) {
        int maxRows = 0;
        int maxCols = 0;
        // 先求所有帧里的最大行数与最大列宽
        for (String f : frames) {
            String[] lines = f.split("\\R", -1);
            maxRows = Math.max(maxRows, lines.length);
            for (String line : lines) {
                maxCols = Math.max(maxCols, line.length());
            }
        }
        // 再逐帧补齐
        for (int i = 0; i < frames.length; i++) {
            String[] lines = frames[i].split("\\R", -1);
            StringBuilder sb = new StringBuilder();
            for (int r = 0; r < maxRows; r++) {
                String line = r < lines.length ? lines[r] : "";
                sb.append(line);
                for (int c = line.length(); c < maxCols; c++) {
                    sb.append(' ');          // 右侧补空格到统一列宽
                }
                if (r < maxRows - 1) {
                    sb.append('\n');
                }
            }
            frames[i] = sb.toString();
        }
    }

    /**
     * 读取 classpath 下的文本资源并原样返回（保留换行）。
     * 用于加载 ASCII 图案等纯文本资源。
     *
     * @param path 资源路径，例如 "/ascii/train.txt"
     * @return 文件内容；读取失败时返回空串（不影响界面）
     */
    private String loadResourceText(String path) {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) {
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** 返回主界面根节点，供 {@link MQBrowserApp} 放入场景。 */
    public BorderPane getRoot() {
        return root;
    }
}
