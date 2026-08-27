package org.bear.mqclient.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 应用外部化配置绑定（application.yml 中的 app.*）。
 * <p>
 * 示例：
 * <pre>
 * app:
 *   window:
 *     width: 1200
 *     height: 800
 *   theme: light
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Window window = new Window();
    private String theme = "light";

    public Window getWindow() { return window; }
    public void setWindow(Window window) { this.window = window; }

    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }

    public static class Window {
        private int width = 1200;
        private int height = 800;

        public int getWidth() { return width; }
        public void setWidth(int width) { this.width = width; }

        public int getHeight() { return height; }
        public void setHeight(int height) { this.height = height; }
    }
}
