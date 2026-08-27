package io.mqclient.core.model;

/**
 * 消息体展示编码格式。
 * 由各 MQ 的 MessageDetailViewFactory 声明支持哪些格式。
 */
public enum BodyEncoding {
    RAW("原始字节"),
    TEXT("纯文本"),
    HEX("十六进制"),
    JSON("JSON"),
    XML("XML"),
    BASE64("Base64");

    private final String displayName;

    BodyEncoding(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() { return displayName; }
}
