package com.mqbrowser.ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.transform.Rotate;
import javafx.util.Duration;

import java.time.LocalTime;

/**
 * 传统的模拟三针时钟：圆形表盘 + 12 个刻度 + 时/分/秒三根指针。
 * 使用 Timeline 每秒刷新一次，秒针真实走动。
 */
public class AnalogClock extends Pane {

    private final Rotate hourRotate = new Rotate(0);     // 时针旋转
    private final Rotate minuteRotate = new Rotate(0);   // 分针旋转
    private final Rotate secondRotate = new Rotate(0);   // 秒针旋转

    /**
     * @param size 表盘直径（像素）
     */
    public AnalogClock(double size) {
        double r = size / 2;              // 半径
        double cx = r, cy = r;            // 圆心

        setPrefSize(size, size);
        setMaxSize(size, size);
        setMinSize(size, size);

        // 表盘外圈
        Circle dial = new Circle(cx, cy, r - 2);
        dial.setFill(Color.WHITE);
        dial.setStroke(Color.web("#37474f"));
        dial.setStrokeWidth(2);
        getChildren().add(dial);

        // 12 个小时刻度（整点较长较粗，其余较短）
        for (int i = 0; i < 12; i++) {
            double angle = Math.toRadians(i * 30);   // 每个整点相隔 30°
            double outer = r - 8;
            double inner = r - 18;
            double sin = Math.sin(angle);
            double cos = Math.cos(angle);
            Line tick = new Line(
                    cx + inner * sin, cy - inner * cos,
                    cx + outer * sin, cy - outer * cos);
            tick.setStroke(Color.web("#37474f"));
            tick.setStrokeWidth(2.5);
            getChildren().add(tick);
        }

        // 时针：短而粗
        Line hour = new Line(cx, cy, cx, cy - r * 0.5);
        hour.setStroke(Color.web("#263238"));
        hour.setStrokeWidth(5);
        hour.getTransforms().add(hourRotate);
        setPivot(hourRotate, cx, cy);

        // 分针：长而略细
        Line minute = new Line(cx, cy, cx, cy - r * 0.72);
        minute.setStroke(Color.web("#263238"));
        minute.setStrokeWidth(3.5);
        minute.getTransforms().add(minuteRotate);
        setPivot(minuteRotate, cx, cy);

        // 秒针：最长最细，用红色
        Line second = new Line(cx, cy + r * 0.16, cx, cy - r * 0.8);
        second.setStroke(Color.web("#e53935"));
        second.setStrokeWidth(1.5);
        second.getTransforms().add(secondRotate);
        setPivot(secondRotate, cx, cy);

        getChildren().addAll(hour, minute, second);

        // 中心轴
        Circle knob = new Circle(cx, cy, 5, Color.web("#263238"));
        getChildren().add(knob);

        // 先立即对准当前时间，再每秒刷新
        update();
        Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> update()));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
    }

    /** 设置旋转变换的支点（枢轴）为圆心。 */
    private void setPivot(Rotate rotate, double px, double py) {
        rotate.setPivotX(px);
        rotate.setPivotY(py);
    }

    /** 根据当前系统时间更新三根指针的角度。 */
    private void update() {
        LocalTime now = LocalTime.now();
        double seconds = now.getSecond();
        double minutes = now.getMinute() + seconds / 60.0;
        double hours = (now.getHour() % 12) + minutes / 60.0;

        secondRotate.setAngle(seconds * 6);    // 每秒 6°
        minuteRotate.setAngle(minutes * 6);    // 每分 6°
        hourRotate.setAngle(hours * 30);       // 每小时 30°
    }
}
