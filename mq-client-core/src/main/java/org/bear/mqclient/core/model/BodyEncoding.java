package org.bear.mqclient.core.model;

import org.bear.mqclient.core.util.I18n;

import java.util.Locale;

/**
 * 消息体展示编码格式。
 * 由各 MQ 的 MessageDetailViewFactory 声明支持哪些格式。
 */
public enum BodyEncoding {
    RAW,
    TEXT,
    HEX,
    JSON,
    XML,
    BASE64;

    /** 显示名（国际化，key: encoding.{name小写}） */
    public String getDisplayName() {
        return I18n.get("encoding." + name().toLowerCase(Locale.ROOT));
    }
}
