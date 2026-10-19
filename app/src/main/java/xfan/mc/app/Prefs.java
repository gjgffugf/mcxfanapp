package xfan.mc.app;

import android.content.Context;
import android.content.SharedPreferences;

/** 本地存储：登录令牌、主题、公告与问卷的隐藏状态等 */
public final class Prefs {

    private static final String FILE = "xfan_prefs";
    public static final String K_TOKEN = "token";
    public static final String K_USER = "user";
    public static final String K_THEME = "theme";
    public static final String K_OWNED = "owned_themes";       // 已购主题，逗号分隔
    public static final String K_UPDATE_IGNORE = "update_ignore_ver";
    public static final String K_NOTICE = "notice_text";        // 上次公告内容
    public static final String K_BANNER_HIDDEN = "banner_hidden";

    private static SharedPreferences sp;

    public static void init(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String get(String k, String def) {
        return sp == null ? def : sp.getString(k, def);
    }

    public static void set(String k, String v) {
        if (sp == null) return;
        sp.edit().putString(k, v).apply();
    }

    public static void remove(String k) {
        if (sp == null) return;
        sp.edit().remove(k).apply();
    }

    public static boolean isLogin() {
        return !get(K_TOKEN, "").isEmpty();
    }

    /* ---- 已购主题 ---- */

    public static java.util.List<String> ownedThemes() {
        java.util.List<String> l = new java.util.ArrayList<>();
        String s = get(K_OWNED, "");
        if (s.isEmpty()) return l;
        for (String x : s.split(",")) if (!x.trim().isEmpty()) l.add(x.trim());
        return l;
    }

    public static void setOwnedThemes(java.util.List<String> list) {
        set(K_OWNED, android.text.TextUtils.join(",", list));
    }
}
