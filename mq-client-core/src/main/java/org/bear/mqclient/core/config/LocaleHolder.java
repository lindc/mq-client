package org.bear.mqclient.core.config;

import java.util.Locale;

/**
 * 当前界面语言（Locale）的持有者。
 * <p>
 * 默认简体中文。切换语言时调用 {@link #setLocale(Locale)}，
 * 菜单栏等 UI 据此重新构建即可实时换语。
 * <p>
 * 本应用为单窗口桌面程序，应用生命周期内保持单例即可。
 */
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
