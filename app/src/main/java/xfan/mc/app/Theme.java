package xfan.mc.app;

import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Arrays;
import java.util.List;

/**
 * 主题：与网页端预设一一对应（价格也一致）。
 * 网页靠换 CSS 实现，App 里没有 CSS，所以集中在配色表。
 */
public final class Theme {

    public static final String DEFAULT = "default";
    public static final String GOLD = "gold";
    public static final String RAINBOW = "rainbow";
    public static final String NY2027 = "ny2027";

    /** 价格：0 免费；>0 需要积分兑换（与网页 app.js 的 THEMES.price 一致） */
    public static final int P_GOLD = 333;
    public static final int P_RAINBOW = 666;
    public static final int P_NY2027 = 700;

    /** 是否上架（与网页 THEME_ENABLED 对应；false = 仅已购用户可见） */
    public static final boolean ON_GOLD = true;
    public static final boolean ON_RAINBOW = true;
    public static final boolean ON_NY2027 = false;   // 2027 限定先不上架

    private static String cur = DEFAULT;

    public static int price(String id) {
        switch (id) {
            case GOLD: return P_GOLD;
            case RAINBOW: return P_RAINBOW;
            case NY2027: return P_NY2027;
            default: return 0;
        }
    }

    public static boolean enabled(String id) {
        switch (id) {
            case GOLD: return ON_GOLD;
            case RAINBOW: return ON_RAINBOW;
            case NY2027: return ON_NY2027;
            default: return true;      // 默认主题永远可用
        }
    }

    public static String name(String id) {
        switch (id) {
            case GOLD: return "金红华章";
            case RAINBOW: return "七色渐变";
            case NY2027: return "2027 限定 · 新春";
            default: return "默认";
        }
    }

    public static String desc(String id) {
        switch (id) {
            case GOLD: return "金色耐看";
            case RAINBOW: return "动态流动渐变";
            case NY2027: return "金红渐变 · 烟花灯笼";
            default: return "经典绿意";
        }
    }

    public static List<String> all() {
        return Arrays.asList(DEFAULT, GOLD, RAINBOW, NY2027);
    }

    /** 背景渐变色（上 → 下） */
    public static int[] bg(String id) {
        switch (id) {
            case GOLD: return new int[]{0xFFF6D365, 0xFFC31432, 0xFF8B1E2D};
            case RAINBOW: return new int[]{0xFF5FD68F, 0xFF3B1E5A, 0xFF1B6B4A};
            case NY2027: return new int[]{0xFFFFD700, 0xFFC62828, 0xFF7F1010};
            default: return new int[]{0xFF16241F, 0xFF0F2017, 0xFF0A1512};
        }
    }

    public static int accent(String id) {
        switch (id) {
            case GOLD: return 0xFFFFD700;
            case RAINBOW: return 0xFF5FD68F;
            case NY2027: return 0xFFFFC107;
            default: return 0xFF5FD68F;
        }
    }

    /** 金红/新春背景亮，文字要用深色才看得清 */
    public static int text(String id) {
        switch (id) {
            case GOLD: return 0xFF4A1206;
            case NY2027: return 0xFF4A1000;
            default: return 0xFFEAF6EF;
        }
    }

    public static int textDim(String id) {
        switch (id) {
            case GOLD: return 0xFF7A3C1A;
            case NY2027: return 0xFF7A3A10;
            default: return 0xB3EAF6EF;
        }
    }

    public static String current() { return cur; }

    public static void setCurrent(String id) { cur = id; }

    /** 应用主题到根布局 + 递归改文字色 */
    public static void apply(View root, String id) {
        cur = id;
        int[] c = bg(id);
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{c[0], c[2]});
        root.setBackground(gd);
        applyText(root, text(id), textDim(id));
    }

    public static void applyText(View v, int color, int dim) {
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyText(g.getChildAt(i), color, dim);
            return;
        }
        if (v instanceof TextView) {
            // 标题类用主色，正文类用暗色：按字号粗略区分
            float size = ((TextView) v).getTextSize();
            ((TextView) v).setTextColor(size >= 15 ? color : dim);
        }
    }

    public static GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp);
        return g;
    }
}
