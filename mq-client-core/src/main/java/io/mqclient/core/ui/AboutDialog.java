package io.mqclient.core.ui;

import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.time.LocalTime;

/**
 * 关于对话框。
 * <p>
 * 展示应用信息，并绘制一个走动的模拟时钟（沿用旧版设计中的时钟元素）。
 * 旧版的 ASCII 小火车动画资源在重构为 SPI 架构时丢失，这里以时钟代替。
 */
public class AboutDialog extends Dialog<Void> {

    public AboutDialog() {
        setTitle("关于 MQBrowser");
        setHeaderText("MQBrowser — 统一消息队列桌面客户端");

        VBox info = new VBox(6);
        info.setPadding(new Insets(10));
        info.getChildren().addAll(
                new Label("版本：1.0.0-SNAPSHOT"),
                new Label("JavaFX + Spring Boot 4.1.1"),
                new Label("支持：RabbitMQ / IBM MQ / Kafka / Artemis / RocketMQ"),
                new Label("© 2026 MQBrowser")
        );

        ClockCanvas clock = new ClockCanvas(160);

        BorderPane content = new BorderPane();
        content.setCenter(info);
        content.setRight(clock);
        content.setPadding(new Insets(10));

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(
                new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE));
        getDialogPane().setPrefSize(460, 240);

        setOnCloseRequest(e -> clock.stop());
    }

    /** 自绘模拟时钟（每秒走动）。 */
    private static class ClockCanvas extends Canvas {
        private final AnimationTimer timer;

        public ClockCanvas(double size) {
            super(size, size);
            timer = new AnimationTimer() {
                @Override
                public void handle(long now) {
                    draw();
                }
            };
            timer.start();
        }

        private void draw() {
            double w = getWidth(), h = getHeight();
            double cx = w / 2, cy = h / 2, r = Math.min(w, h) / 2 - 6;
            GraphicsContext g = getGraphicsContext2D();
            g.clearRect(0, 0, w, h);

            g.setStroke(Color.BLACK);
            g.setLineWidth(2);
            g.strokeOval(cx - r, cy - r, r * 2, r * 2);

            g.setLineWidth(1);
            for (int i = 0; i < 12; i++) {
                double a = Math.toRadians(i * 30 - 90);
                g.strokeLine(cx + r * 0.85 * Math.cos(a), cy + r * 0.85 * Math.sin(a),
                        cx + r * Math.cos(a), cy + r * Math.sin(a));
            }

            LocalTime t = LocalTime.now();
            double sec = t.getSecond() + t.getNano() / 1e9;
            double min = t.getMinute() + sec / 60;
            double hr = (t.getHour() % 12) + min / 60;
            drawHand(g, cx, cy, r * 0.5, hr * 30, 3, Color.BLACK);
            drawHand(g, cx, cy, r * 0.7, min * 6, 2, Color.BLACK);
            drawHand(g, cx, cy, r * 0.85, sec * 6, 1, Color.RED);
        }

        private void drawHand(GraphicsContext g, double cx, double cy,
                              double len, double angleDeg, double width, Color c) {
            double a = Math.toRadians(angleDeg - 90);
            g.setStroke(c);
            g.setLineWidth(width);
            g.strokeLine(cx, cy, cx + len * Math.cos(a), cy + len * Math.sin(a));
        }

        public void stop() {
            timer.stop();
        }
    }
}
