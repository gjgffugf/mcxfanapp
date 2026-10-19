package xfan.mc.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.text.TextUtils;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * App 版本更新。
 *
 * 每次启动都会<b>并发请求四个接口源</b>，每个源都试 CSS / JSONP / GET / POST
 * 四种通道，取回版本号最大的那个 —— 这样只要有一个源能穿过 WAF 就能拿到。
 *
 * 后台（admin.php → APP 栏目）填的版本号大于本机版本时弹更新窗，
 * 内容是版本名 + 更新内容 + 「前往下载」（跳浏览器）。
 */
public final class UpdateManager {

    /** 本机版本号；读不到返回 0 */
    @SuppressWarnings("deprecation")
    public static int localVersionCode(Activity act) {
        try {
            PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            return pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    public static void check(Activity act) {
        check(act, null);
    }

    /** done：检查完成回调（无论有没有更新都会调用），可为 null */
    public static void check(Activity act, Runnable done) {
        final int local = localVersionCode(act);

        // 四个源并发探测，谁先返回可用结果且版本号最大就用谁
        final JSONObject best = new JSONObject();
        final int[] bestCode = {0};
        final AtomicInteger left = new AtomicInteger(Api.BASES.length);
        final Object lock = new Object();

        for (final String base : Api.BASES) {
            new Thread(() -> {
                JSONObject j = fetchVersion(base);
                if (j != null) {
                    synchronized (lock) {
                        int code = j.optInt("version_code", 0);
                        if (code > bestCode[0]) {
                            bestCode[0] = code;
                            copyInto(best, j);
                        }
                    }
                }
                if (left.decrementAndGet() == 0) {
                    final int remote = bestCode[0];
                    act.runOnUiThread(() -> {
                        if (remote > 0 && remote > local
                                && !TextUtils.isEmpty(best.optString("download_url", ""))) {
                            show(act, remote, best);
                        }
                        if (done != null) done.run();
                    });
                }
            }).start();
        }
    }

    /** 单个源：逐个通道试，拿到 ok:true 且 version_code>0 就返回 */
    private static JSONObject fetchVersion(String base) {
        for (String ch : Api.DATA_CHANNELS) {
            JSONObject j = null;
            switch (ch) {
                case Api.CH_CSS:   j = tryCss(base); break;
                case Api.CH_JSONP: j = tryJsonp(base); break;
                case Api.CH_GET:   j = tryGet(base); break;
                case Api.CH_POST:  j = tryPost(base); break;
            }
            if (j != null && j.optBoolean("ok", false) && j.optInt("version_code", 0) > 0) {
                return j;
            }
        }
        return null;
    }

    private static String qs(String extra) {
        return "action=app_version&_=" + System.currentTimeMillis() + extra;
    }

    private static JSONObject pick(String text, boolean jsonp) {
        if (text == null) return null;
        String t = text.trim();
        if (jsonp) {
            int a = t.indexOf('(');
            int b = t.lastIndexOf(')');
            if (a < 0 || b <= a) return null;
            t = t.substring(a + 1, b).trim();
        }
        if (!t.startsWith("{")) return null;
        try { return new JSONObject(t); } catch (Exception e) { return null; }
    }

    private static JSONObject tryCss(String base) {
        String text = Api.httpGet(base + "?" + qs("&transport=css"), "text/css,*/*");
        if (text == null) return null;
        int k = text.indexOf("--xfan-data");
        if (k < 0) return null;
        int a = text.indexOf('"', k), b = (a < 0) ? -1 : text.indexOf('"', a + 1);
        if (b < 0) return null;
        try {
            String json = new String(android.util.Base64.decode(
                    text.substring(a + 1, b), android.util.Base64.DEFAULT),
                    java.nio.charset.StandardCharsets.UTF_8);
            return pick(json, false);
        } catch (Exception e) { return null; }
    }

    private static JSONObject tryJsonp(String base) {
        String cb = "xfanv" + System.nanoTime();
        String text = Api.httpGet(base + "?" + qs("&callback=" + cb),
                "application/javascript,*/*");
        return pick(text, true);
    }

    private static JSONObject tryGet(String base) {
        return pick(Api.httpGet(base + "?" + qs(""), "application/json,*/*"), false);
    }

    private static JSONObject tryPost(String base) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(base).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            byte[] body = qs("").getBytes("UTF-8");
            c.setRequestProperty("Content-Length", String.valueOf(body.length));
            c.getOutputStream().write(body);
            c.getOutputStream().flush();
            c.getOutputStream().close();
            java.io.InputStream is = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return pick(new String(bos.toByteArray(), "UTF-8"), false);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void copyInto(JSONObject dst, JSONObject src) {
        try {
            dst.put("version_code", src.optInt("version_code", 0));
            dst.put("version_name", src.optString("version_name", ""));
            dst.put("changelog", src.optString("changelog", ""));
            dst.put("download_url", src.optString("download_url", ""));
            dst.put("force", src.optInt("force", 0));
        } catch (Exception ignored) {
        }
    }

    private static void show(Activity act, int code, JSONObject j) {
        String name = j.optString("version_name", String.valueOf(code));
        String log = j.optString("changelog", "修复了一些问题");
        String url = j.optString("download_url", "");
        boolean force = j.optInt("force", 0) == 1;

        String ignore = Prefs.get(Prefs.K_UPDATE_IGNORE, "0");
        if (!force && String.valueOf(code).equals(ignore)) return;   // 选过「以后再说」

        TextView tv = new TextView(act);
        tv.setText("新版本 " + name + "\n\n" + log);
        tv.setTextColor(0xFFEAF6EF);
        tv.setTextSize(14);
        int pad = (int) (20 * act.getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad / 2);

        AlertDialog.Builder b = new AlertDialog.Builder(act)
                .setTitle("发现新版本")
                .setView(tv)
                .setPositiveButton("前往下载", (d, w) -> {
                    if (!TextUtils.isEmpty(url)) {
                        act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    }
                });
        if (!force) {
            b.setNegativeButton("以后再说",
                    (d, w) -> Prefs.set(Prefs.K_UPDATE_IGNORE, String.valueOf(code)));
        }
        b.setCancelable(!force);
        try { b.show(); } catch (Exception ignored) {}
    }
}
