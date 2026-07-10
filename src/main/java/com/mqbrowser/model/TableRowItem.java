package com.mqbrowser.model;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * 表格通用的三列行数据模型（名称 / 类型 / 值）。
 * 使用 JavaFX 属性（Property），当值变化时 TableView 单元格会自动刷新。
 */
public class TableRowItem {

    // 三个可观察字符串属性，分别对应表格的三列
    private final StringProperty name = new SimpleStringProperty(this, "name");
    private final StringProperty type = new SimpleStringProperty(this, "type");
    private final StringProperty value = new SimpleStringProperty(this, "value");

    public TableRowItem(String name, String type, String value) {
        this.name.set(name);
        this.type.set(type);
        this.value.set(value);
    }

    // ===== 属性访问器：供 TableColumn 的 PropertyValueFactory 绑定使用 =====

    public StringProperty nameProperty() {
        return name;
    }

    public StringProperty typeProperty() {
        return type;
    }

    public StringProperty valueProperty() {
        return value;
    }

    // ===== 普通取值方法 =====

    public String getName() {
        return name.get();
    }

    public String getType() {
        return type.get();
    }

    public String getValue() {
        return value.get();
    }
}
