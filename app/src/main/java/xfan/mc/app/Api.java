package xfan.mc.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 接口请求层。
 *
 * 原生 App 没有浏览器跨域限制，但源站前面挂了 WAF，
 * 所以这里复刻网页端的多通道思路 —— 用不同「外形」的请求去试，
 * 总有一种能穿过 WAF。
 *
 * <b>五种通道</b>（按被 WAF 放行的概率从高到低排列）：
 * <ol>
 *   <li><b>CSS</b>：请求返回 text/css，看起来就是个样式表，
 *       最容易被当成静态资源放行；数据用 base64 塞在 CSS 变量里</li>
 *   <li><b>JSONP</b>：返回 application/javascript，同样像静态资源</li>
 *   <li><b>GET</b>：普通查询串</li>
 *   <li><b>POST</b>：标准表单提交，最容易触发 WAF 规则</li>
 *   <li><b>IMG</b>：只确认服务器有没有响应，拿不到数据</li>
 * </ol>
 *
 * <b>写操作保护</b>：登录/注册/签到这类会改数据的操作，
 * 一旦「连上了但没等到回应」（超时）就立刻停止、绝不换通道重试，
 * 否则会被重复执行（签到会变成「已签到过」）。
 */
public final class Api {

    private static final String TAG = "xfan-api";

    /** 四个接口源，与网页 app.js 的 API_LIST 一致 */
    public static final String[] BASES = {
            "https://xiaof.hyperphp.com/php/api.php",
            "https://xfan-api.ffurry.cc.cd/",
            "https://xfan-api.xfan.ccwu.cc/",
            "https://ffurry.hyperphp.com/php/api.php"
    };

    public static final int TIMEOUT = 15000;

    /** 通道标识 */
    public static final String CH_CSS = "css";
    public static final String CH_JSONP = "jsonp";
    public static final String CH_GET = "get";
    public static final String CH_POST = "post";
    public static final String CH_IMG = "img";

    /** 数据通道顺序：能拿数据的四种，img 只作探针不在此列 */
    public static final String[] DATA_CHANNELS = {CH_CSS, CH_JSONP, CH_GET, CH_POST};

    public interface Cb {
        /** j 为 null 表示所有通道/地址都失败 */
        void on(JSONObject j);
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 上次失败是不是「压根没连上」（DNS失败/被拒），true 表示可以继续换地址 */
    private static volatile boolean lastNoConn = false;

    /** 成功的通道，下次优先用 */
    private static String goodChannel = null;

    /** 统计用：哪个通道成功过 */
    public interface ChanLog { void on(String chan, boolean ok); }
    private static ChanLog chanLog;

    public static void setChanLog(ChanLog l) { chanLog = l; }

    private static void logChan(String c, boolean ok) {
        if (chanLog != null) MAIN.post(() -> chanLog.on(c, ok));
    }

    public static String goodChannel() { return goodChannel; }

    /* ---------------- 对外接口 ---------------- */

    /** 读操作：可以逐个通道、逐个地址重试 */
    public static void post(String action, Map<String, String> data, Cb cb) {
        go(action, data, cb, false);
    }

    /** 写操作：超时即停，绝不重试 */
    public static void postWrite(String action, Map<String, String> data, Cb cb) {
        go(action, data, cb, true);
    }

    /**
     * 探测某个地址是否能通（返回 ok:true 即通），并给出耗时与成功的通道。
     * 用于接口状态灯。
     */
    public static void probe(String base, ProbeCb cb) {
        String[] order = preferredOrder();
        final long t0 = System.currentTimeMillis();
        final AtomicInteger i = new AtomicInteger(0);
        final boolean[] done = {false};
        POOL.execute(() -> {
            for (String ch : order) {
                if (done[0]) return;
                JSONObject j = tryChannel(base, "ping", null, ch);
                boolean ok = j != null && j.optBoolean("ok", false);
                logChan(ch, ok);
                if (ok) {
                    done[0] = true;
                    long ms = System.currentTimeMillis() - t0;
                    MAIN.post(() -> cb.on(true, ms, ch));
                    return;
                }
            }
            // 四种数据通道都不行，最后用 img 探针确认服务器到底有没有响应
            boolean alive = imgBeacon(base);
            long ms = System.currentTimeMillis() - t0;
            MAIN.post(() -> cb.on(false, ms, alive ? "img(有响应但拿不到数据)" : ""));
        });
    }

    public interface ProbeCb { void on(boolean ok, long ms, String chan); }

    /* ---------------- 核心调度 ---------------- */

    private static void go(String action, Map<String, String> data, Cb cb, boolean write) {
        final String[] order = preferredOrder();
        POOL.execute(() -> {
            JSONObject result = null;
            String usedChan = null;

            for (String base : BASES) {
                for (String ch : order) {
                    JSONObject j = tryChannel(base, action, data, ch);
                    logChan(ch, j != null && j.optBoolean("ok", false));

                    boolean serverAnswered = (j != null);
                    if (serverAnswered) {
                        // 服务端明确回答了（哪怕 ok:false 也是回答），不再换通道
                        result = j;
                        usedChan = ch;
                        break;
                    }
                    if (write && !lastNoConn) {
                        /* 写操作 + 超时（连上了但没回话）：请求可能已执行，立即停止 */
                        Log.w(TAG, action + " 写操作超时，停止重试（防止重复执行）");
                        JSONObject fin = result;
                        MAIN.post(() -> { if (cb != null) cb.on(fin); });
                        return;
                    }
                }
                if (result != null) break;
                if (write && !lastNoConn) {
                    MAIN.post(() -> { if (cb != null) cb.on(null); });
                    return;
                }
            }

            if (usedChan != null) goodChannel = usedChan;
            JSONObject fin = result;
            MAIN.post(() -> { if (cb != null) cb.on(fin); });
        });
    }

    /** 通道顺序：上次成功的排最前 */
    private static String[] preferredOrder() {
        List<String> l = new ArrayList<>();
        if (goodChannel != null) l.add(goodChannel);
        for (String c : DATA_CHANNELS) if (!l.contains(c)) l.add(c);
        return l.toArray(new String[0]);
    }

    /* ---------------- 单通道请求 ---------------- */

    /** 用指定通道请求一次；返回 null 表示这个通道没拿到服务端回答 */
    private static JSONObject tryChannel(String base, String action,
                                         Map<String, String> data, String chan) {
        lastNoConn = false;
        try {
            switch (chan) {
                case CH_CSS:   return chCss(base, action, data);
                case CH_JSONP: return chJsonp(base, action, data);
                case CH_GET:   return chGet(base, action, data);
                case CH_POST:  return chPost(base, action, data);
                default:       return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 通道1：CSS 变量。
     * 请求 <code>?transport=css</code>，服务端返回
     * <code>#xfan-css-probe{--xfan-data:"base64"}</code>。
     * 响应类型是 text/css，WAF 通常当静态资源放行 —— 这是穿过 WAF 概率最高的通道。
     */
    private static JSONObject chCss(String base, String action, Map<String, String> data) {
        Map<String, String> d = withAuth(action, data);
        d.put("transport", "css");
        String url = base + "?" + query(d);
        String text = httpGet(url, "text/css,*/*");
        if (text == null) return null;
        // 提取 --xfan-data:"...."
        int k = text.indexOf("--xfan-data");
        if (k < 0) return null;
        int a = text.indexOf('"', k);
        if (a < 0) return null;
        int b = text.indexOf('"', a + 1);
        if (b < 0) return null;
        String b64 = text.substring(a + 1, b);
        String json = new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
        return parse(json);
    }

    /**
     * 通道2：JSONP。
     * 请求带 <code>callback=xxx</code>，服务端返回 <code>xxx({...});</code>。
     * 响应是 application/javascript，同样容易被当静态资源放行。
     */
    private static JSONObject chJsonp(String base, String action, Map<String, String> data) {
        Map<String, String> d = withAuth(action, data);
        String cb = "xfan" + System.nanoTime();
        d.put("callback", cb);
        String url = base + "?" + query(d);
        String text = httpGet(url, "application/javascript,*/*");
        if (text == null) return null;
        int a = text.indexOf('(');
        int b = text.lastIndexOf(')');
        if (a < 0 || b <= a) return null;
        return parse(text.substring(a + 1, b));
    }

    /** 通道3：普通 GET */
    private static JSONObject chGet(String base, String action, Map<String, String> data) {
        Map<String, String> d = withAuth(action, data);
        String text = httpGet(base + "?" + query(d), "application/json,*/*");
        return text == null ? null : parse(text);
    }

    /** 通道4：标准 POST 表单 */
    private static JSONObject chPost(String base, String action, Map<String, String> data) {
        HttpURLConnection c = null;
        try {
            Map<String, String> d = withAuth(action, data);
            byte[] body = query(d).getBytes(StandardCharsets.UTF_8);
            c = (HttpURLConnection) new URL(base).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(true);
            applyBrowserHeaders(c, "application/json,*/*");
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("Content-Length", String.valueOf(body.length));
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.flush();
            os.close();
            return readJson(c);
        } catch (SocketTimeoutException e) {
            lastNoConn = false;   // 连上了但没回话
            return null;
        } catch (Exception e) {
            lastNoConn = true;    // 压根没连上
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * 通道5：img 探针（只测连通，拿不到数据）。
     * 请求 <code>?transport=img</code> 会返回 1x1 GIF。
     */
    private static boolean imgBeacon(String base) {
        try {
            Map<String, String> d = new LinkedHashMap<>();
            d.put("action", "ping");
            d.put("transport", "img");
            d.put("_", String.valueOf(System.currentTimeMillis()));
            String text = httpGet(base + "?" + query(d), "image/*,*/*");
            return text != null && !text.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /* ---------------- 底层 HTTP ---------------- */

    /** GET 取文本（给 MC 状态这类第三方接口用）；失败返回 null */
    public static String getText(String url, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("GET");
            c.setUseCaches(false);
            applyBrowserHeaders(c, "*/*");
            return readAll(c);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** GET 取文本；失败返回 null */
    public static String httpGet(String url, String accept) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            c.setRequestMethod("GET");
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(true);
            applyBrowserHeaders(c, accept);
            return readAll(c);
        } catch (SocketTimeoutException e) {
            lastNoConn = false;
            return null;
        } catch (Exception e) {
            lastNoConn = true;
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * 伪装成浏览器的请求头。
     * WAF 常常直接放行「看起来像浏览器」的请求，拦掉裸的 HttpClient。
     */
    private static void applyBrowserHeaders(HttpURLConnection c, String accept) {
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        c.setRequestProperty("Accept-Encoding", "identity");   // 不压缩，省去解压
        c.setRequestProperty("Connection", "keep-alive");
        c.setRequestProperty("Referer", "https://xiaof.hyperphp.com/php/");
        c.setRequestProperty("X-Requested-With", "");
        c.setRequestProperty("Origin", "https://xiaof.hyperphp.com");
        c.setRequestProperty("Cache-Control", "no-cache");
    }

    /** 真实浏览器 UA，降低被识别为机器人的概率 */
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /** 读取响应并按 JSON 解析；非 JSON（防爬页/HTML）返回 null */
    private static JSONObject readJson(HttpURLConnection c) {
        String text = readAll(c);
        return text == null ? null : parse(text);
    }

    private static String readAll(HttpURLConnection c) {
        InputStream is = null;
        try {
            int code = c.getResponseCode();
            is = (code >= 400) ? c.getErrorStream() : c.getInputStream();
            if (is == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            String s = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            if (s.trim().isEmpty()) return null;
            return s;
        } catch (SocketTimeoutException e) {
            lastNoConn = false;
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (is != null) { try { is.close(); } catch (Exception ignored) {} }
        }
    }

    private static JSONObject parse(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (!t.startsWith("{")) return null;   // 防爬页是 HTML
        try {
            return new JSONObject(t);
        } catch (JSONException e) {
            return null;
        }
    }

    /* ---------------- 工具 ---------------- */

    /** 组装参数：action + token + 业务数据 */
    private static Map<String, String> withAuth(String action, Map<String, String> data) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("action", action);
        String token = Prefs.get(Prefs.K_TOKEN, "");
        if (!token.isEmpty()) m.put("token", token);
        if (data != null) m.putAll(data);
        return m;
    }

    private static String query(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            if (!first) sb.append('&');
            first = false;
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return sb.toString();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    public static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** 短名，给接口状态灯显示 */
    public static String shortName(String url) {
        if (url.contains("xfan-api.ffurry")) return "代理";
        if (url.contains("xfan-api.xfan")) return "代理2";
        if (url.contains("xiaof.hyperphp")) return "直连接口";
        if (url.contains("ffurry.hyperphp")) return "备用1";
        return url;
    }

    public static void resetChannel() { goodChannel = null; }
}
