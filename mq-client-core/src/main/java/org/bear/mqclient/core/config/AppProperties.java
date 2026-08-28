package org.bear.mqclient.core.config;

import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * 应用外部化配置（application.yml 中的 app.*）。
 * <p>
 * 示例：
 * <pre>
 * app:
 *   window:
 *     width: 1200
 *     height: 800
 *   theme: light
 * </pre>
 * 由 {@link #load()} 在启动时从 classpath 读取；文件缺失或字段缺失时使用默认值。
 */
@Slf4j
public class AppProperties {

    private Window window = new Window();
    private String theme = "light";

    /** 从 classpath 的 application.yml 加载（snakeyaml 直接解析，无框架依赖） */
    @SuppressWarnings("unchecked")
    public static AppProperties load() {
        AppProperties props = new AppProperties();
        try (InputStream in = AppProperties.class.getResourceAsStream("/application.yml")) {
            if (in == null) {
                log.info("application.yml not found, using defaults");
                return props;
            }
            Map<String, Object> root = new Yaml().load(in);
            if (root == null) return props;

            Object app = root.get("app");
            if (app instanceof Map<?, ?> appMap) {
                Object windowObj = appMap.get("window");
                if (windowObj instanceof Map<?, ?> w) {
                    Object width = w.get("width");
                    if (width instanceof Number n) props.window.setWidth(n.intValue());
                    Object height = w.get("height");
                    if (height instanceof Number n) props.window.setHeight(n.intValue());
                }
                Object theme = appMap.get("theme");
                if (theme instanceof String s) props.theme = s;
            }
        } catch (IOException e) {
            log.warn("Failed to load application.yml: {}", e.getMessage());
        }
        return props;
    }

    public Window getWindow() { return window; }
    public void setWindow(Window window) { this.window = window; }

    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }

    /** 窗口尺寸 */
    public static class Window {
        private int width = 1200;
        private int height = 800;

        public int getWidth() { return width; }
        public void setWidth(int width) { this.width = width; }

        public int getHeight() { return height; }
        public void setHeight(int height) { this.height = height; }
    }
}
