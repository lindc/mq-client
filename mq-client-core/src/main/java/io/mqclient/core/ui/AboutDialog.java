package io.mqclient.core.ui;

import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import io.mqclient.core.config.LocaleHolder;
import org.springframework.context.MessageSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 关于对话框。
 * <p>
 * 左侧一个走动的模拟时钟；右侧一列「冒烟小火车」ASCII 动画
 * （烟雾帧来自 resources/ascii/smoke.txt，循环播放形成飘动效果，
 * 车身来自 resources/ascii/train.txt）。
 */
public class AboutDialog extends Dialog<Void> {

    private final List<String> smokeFrames = new ArrayList<>();
    private final String train;
    private final ClockCanvas clock = new ClockCanvas(150);
    private final MessageSource messageSource;
    private final LocaleHolder localeHolder;
    private Timeline smokeTimeline;

    public AboutDialog(MessageSource messageSource, LocaleHolder localeHolder) {
        this.messageSource = messageSource;
        this.localeHolder = localeHolder;

        setTitle(msg("about.title"));
        setHeaderText(msg("about.header"));

        train = loadResource("/ascii/train.txt").stripTrailing();
        loadSmokeFrames(loadResource("/ascii/smoke.txt"));

        Label smokeLabel = new Label(buildArt(0));
        smokeLabel.setFont(Font.font("Monospaced", 13));

        Label info = new Label(msg("about.version") + "\n"
                + msg("about.tech") + "\n"
                + msg("about.supported") + "\n"
                + msg("about.copyright"));
        info.setPadding(new Insets(0, 0, 8, 0));

        VBox trainBox = new VBox(6, info, smokeLabel);

        HBox center = new HBox(24, clock, trainBox);
        center.setPadding(new Insets(10));

        getDialogPane().setContent(center);
        getDialogPane().getButtonTypes().add(
                new ButtonType(msg("about.close"), ButtonBar.ButtonData.CANCEL_CLOSE));
        getDialogPane().setPrefSize(560, 300);

        if (!smokeFrames.isEmpty()) {
            final int[] idx = {0};
            smokeTimeline = new Timeline(new KeyFrame(Duration.millis(350), e -> {
                smokeLabel.setText(buildArt(idx[0]));
                idx[0] = (idx[0] + 1) % smokeFrames.size();
            }));
            smokeTimeline.setCycleCount(Timeline.INDEFINITE);
            smokeTimeline.play();
        }

        setOnCloseRequest(e -> {
            clock.stop();
            if (smokeTimeline != null) smokeTimeline.stop();
        });

        // 设为主窗口的子窗口，避免无 owner 时被主窗口遮挡而不显示
        for (Window w : Stage.getWindows()) {
            if (w.isShowing()) {
                initOwner(w);
                break;
            }
        }
    }

    private String msg(String key) {
        return messageSource.getMessage(key, null, key, localeHolder.getLocale());
    }

    private String buildArt(int frameIndex) {
        String frame = smokeFrames.isEmpty() ? "" : smokeFrames.get(frameIndex % smokeFrames.size());
        return frame + "\n" + train;
    }

    private void loadSmokeFrames(String text) {
        for (String part : text.split("\\n\\s*\\n")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) smokeFrames.add(trimmed);
        }
    }

    private String loadResource(String path) {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) return "";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
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
