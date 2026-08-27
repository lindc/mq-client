package io.mqclient.core.config;

import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 当前界面语言（Locale）的持有者。
 * <p>
 * 默认简体中文。切换语言时调用 {@link #setLocale(Locale)}，
 * 菜单栏等 UI 据此重新构建即可实时换语。
 * <p>
 * 以 prototype/局部状态存在；本应用为单窗口桌面程序，使用单例即可。
 */
@Component
public class LocaleHolder {

    private Locale locale = Locale.SIMPLIFIED_CHINESE;

    public Locale getLocale() {
        return locale;
    }

    public void setLocale(Locale locale) {
        if (locale != null) {
            this.locale = locale;
        }
    }
}
