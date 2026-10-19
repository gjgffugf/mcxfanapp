package xfan.mc.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MC 服务器状态。
 *
 * 数据源和网页端一致；原生 App 没有跨域限制，直接 GET 就行。
 *
 * ⚠️ 取人数的规则：多个源<b>取最大值</b>，ping 取最小值。
 * 各源缓存刷新时间不同，只用某一个源会出现「在线 2 人却显示 1 人」。
 */
public final class McStatus {

    private static final String[] SOURCES = {
            "https://api.mc-api.io/v1/instance/mc.xfan.l.cd",
            "https://api.minecraftpinger.com/ping/mc.xfan.l.cd"
    };

    public static final class Result {
        public boolean online = false;
        public int players = 0;
        public int max = 0;
        public String motd = "";
        public String version = "";
        public long ping = 0;
        public List<String> playerList = new ArrayList<>();
    }

    public interface Cb { void on(Result r); }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    public static void fetch(android.os.Handler main, Cb cb) {
        POOL.execute(() -> {
            Result r = new Result();
            AtomicInteger left = new AtomicInteger(SOURCES.length);
            Object lock = new Object();
            for (String url : SOURCES) {
                POOL.execute(() -> {
                    long t0 = System.currentTimeMillis();
                    String text = Api.getText(url, 8000);
                    long ms = System.currentTimeMillis() - t0;
                    if (text != null) {
                        synchronized (lock) { parseInto(r, text, ms); }
                    }
                    if (left.decrementAndGet() == 0) main.post(() -> cb.on(r));
                });
            }
        });
    }

    /** 把某个源的返回合并进结果：人数取最大、ping 取最小 */
    private static void parseInto(Result r, String text, long ms) {
        try {
            JSONObject j = new JSONObject(text);
            boolean on = j.optBoolean("online", false);
            if (on) r.online = true;

            int online = 0;
            JSONObject pl = j.optJSONObject("players");
            if (pl != null) {
                online = pl.optInt("online", 0);
                int max = pl.optInt("max", 0);
                if (max > r.max) r.max = max;
                JSONArray arr = pl.optJSONArray("list");
                if (arr != null) {
                    List<String> names = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        Object o = arr.get(i);
                        String n = (o instanceof JSONObject)
                                ? ((JSONObject) o).optString("name", "")
                                : String.valueOf(o);
                        if (!n.isEmpty() && !names.contains(n)) names.add(n);
                    }
                    if (names.size() > r.playerList.size()) r.playerList = names;
                }
            }
            // 人数取最大值：源只会滞后少报，不会虚报
            if (online > r.players) r.players = online;

            String motd = j.optString("motd", "");
            if (motd != null && !motd.isEmpty() && r.motd.isEmpty()) r.motd = motd;
            String ver = j.optString("version", "");
            if (ver != null && !ver.isEmpty() && r.version.isEmpty()) r.version = ver;

            if (ms > 0 && (r.ping == 0 || ms < r.ping)) r.ping = ms;
        } catch (Exception ignored) {
        }
    }
}
