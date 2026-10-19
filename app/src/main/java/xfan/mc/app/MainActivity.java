package xfan.mc.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.OvershootInterpolator;
import android.view.animation.TranslateAnimation;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 主界面：完全复刻网页版布局与数据调用。
 *
 * 结构：
 * <ul>
 *   <li>顶部：首页 / 监控 / 反馈 / 更多 + 账号位（切到「我的」时整条隐藏）</li>
 *   <li>底部：首页 / 我的</li>
 * </ul>
 *
 * 启动流程与网页一致：加载动画（四项进度）→ 公告弹窗 → 版本检查。
 */
public class MainActivity extends android.app.Activity {

    private final Handler main = new Handler(Looper.getMainLooper());

    /* 页面 */
    private ScrollView pageHome, pageMonitor, pageFeedback, pageMore, pageMe;
    private View topTabs, loadingRoot, themeWipe;
    private String curPage = "home";

    /* 加载层 */
    private TextView loadStep, loadPct;
    private View loadBar;
    private static final int[] W = {55, 15, 15, 15};   // 四项权重，合计 100

    /* 首页 */
    private TextView introText, rulesText, stOnline, stPlayers, stVersion, stMotd, contactText;
    private LinearLayout surveyBanner;

    /* 监控 */
    private TextView motdText, pingText, playerCountText;
    private LinearLayout playerList, apiList;

    /* 反馈 */
    private LinearLayout fbLock, fbBody, fbMine;
    private Spinner fbType;
    private EditText fbContent;

    /* 更多 */
    private LinearLayout faqList;

    /* 我的 */
    private LinearLayout meGuest, meUser, themeList, meActions;
    private TextView meName, meEmail, mePoints, meHint, meMsg;
    private Button meSign;

    private String noticeText = "";
    private int curPoints = 0;
    private boolean loadingDone = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs.init(this);
        setContentView(R.layout.activity_main);

        bind();
        setupNav();
        setupHome();
        setupMonitor();
        setupFeedback();
        setupMore();
        setupMe();

        String saved = Prefs.get(Prefs.K_THEME, Theme.DEFAULT);
        Theme.apply(findViewById(R.id.root), saved);

        /* 启动流程：加载动画 → 公告 → 版本检查（与网页时序一致） */
        runLoading();
    }

    private void bind() {
        topTabs = findViewById(R.id.topTabs);
        loadingRoot = findViewById(R.id.loadingLayer);
        themeWipe = findViewById(R.id.themeWipe);

        pageHome = findViewById(R.id.pageHome);
        pageMonitor = findViewById(R.id.pageMonitor);
        pageFeedback = findViewById(R.id.pageFeedback);
        pageMore = findViewById(R.id.pageMore);
        pageMe = findViewById(R.id.pageMe);

        loadStep = findViewById(R.id.loadStep);
        loadPct = findViewById(R.id.loadPct);
        loadBar = findViewById(R.id.loadBar);

        introText = findViewById(R.id.introText);
        rulesText = findViewById(R.id.rulesText);
        stOnline = findViewById(R.id.stOnline);
        stPlayers = findViewById(R.id.stPlayers);
        stVersion = findViewById(R.id.stVersion);
        stMotd = findViewById(R.id.stMotd);
        contactText = findViewById(R.id.contactText);
        surveyBanner = findViewById(R.id.surveyBanner);

        motdText = findViewById(R.id.motdText);
        pingText = findViewById(R.id.pingText);
        playerCountText = findViewById(R.id.playerCountText);
        playerList = findViewById(R.id.playerList);
        apiList = findViewById(R.id.apiList);

        fbLock = findViewById(R.id.fbLock);
        fbBody = findViewById(R.id.fbBody);
        fbMine = findViewById(R.id.fbMine);
        fbType = findViewById(R.id.fbType);
        fbContent = findViewById(R.id.fbContent);

        faqList = findViewById(R.id.faqList);

        meGuest = findViewById(R.id.meGuest);
        meUser = findViewById(R.id.meUser);
        themeList = findViewById(R.id.themeList);
        meActions = findViewById(R.id.meActions);
        meName = findViewById(R.id.meName);
        meEmail = findViewById(R.id.meEmail);
        mePoints = findViewById(R.id.mePoints);
        meHint = findViewById(R.id.meHint);
        meMsg = findViewById(R.id.meMsg);
        meSign = findViewById(R.id.meSign);
    }

    /* =============== 加载动画 =============== */

    /**
     * 四项依次执行：加载背景 → 用户数据 → 个性化设置 → 公告。
     * 权重与网页一致（55/15/15/15），进度条不会提前满。
     */
    /** 启动总超时：任何一步卡死都强制放行，避免一直停在加载页 */
    private static final int BOOT_TIMEOUT = 8000;

    private void runLoading() {
        loadingRoot.setVisibility(View.VISIBLE);
        setProgress(0);
        loadingDone = false;

        // 兜底：公告请求迟迟不回也能进主界面
        main.postDelayed(() -> {
            if (!loadingDone) { setProgress(100); finishLoading(); }
        }, BOOT_TIMEOUT);

        step(0, "加载背景中", () -> {
            loadMcStatus();
            step(1, "加载用户数据中", () -> {
                if (!Prefs.isLogin()) { step(2, "加载个性化设置中", this::step4); return; }
                Api.post("me", null, j -> {
                    if (j != null && j.optBoolean("ok", false)) {
                        JSONObject p = j.optJSONObject("profile");
                        if (p != null) {
                            curPoints = p.optInt("points", 0);
                            JSONArray owned = p.optJSONArray("themes");
                            List<String> l = new ArrayList<>();
                            if (owned != null) {
                                for (int i = 0; i < owned.length(); i++) l.add(owned.optString(i));
                            }
                            Prefs.setOwnedThemes(l);
                            String th = p.optString("theme", "");
                            if (!TextUtils.isEmpty(th)) {
                                Prefs.set(Prefs.K_THEME, th);
                                Theme.apply(findViewById(R.id.root), th);
                            }
                        }
                    }
                    step(2, "加载个性化设置中", this::step4);
                });
            });
        });
    }

    /**
     * 第四步：加载公告。
     * 公告在加载期就预取好（与网页一致），动画结束、进度条到 100% 之后才弹窗。
     */
    private void step4() {
        step(3, "加载公告", () -> {
            Api.post("notice", null, j -> {
                if (j != null && j.optBoolean("ok", false)) {
                    noticeText = j.optString("notice", "");
                }
                if (loadingDone) return;    // step 的超时保护可能已先收尾
                setProgress(100);
                finishLoading();
            });
        });
    }

    /** 执行第 i 项：显示文字、推进进度、跑任务（最短展示 50ms，避免一闪而过） */
    private void step(int i, String label, Runnable task) {
        int before = 0;
        for (int k = 0; k < i; k++) before += W[k];
        final int start = before;
        final int end = before + W[i];

        loadStep.setText(label);
        animateStep();
        setProgress(start);

        final long t0 = System.currentTimeMillis();
        final boolean[] done = {false};
        final Runnable[] finish = new Runnable[1];

        finish[0] = () -> {
            if (done[0]) return;
            done[0] = true;
            long used = System.currentTimeMillis() - t0;
            long wait = Math.max(0, 50 - used);
            main.postDelayed(() -> {
                setProgress(end);
                task.run();
            }, wait);
        };

        // 单步超时 3 秒：卡住也往下走
        main.postDelayed(() -> { if (!done[0]) finish[0].run(); }, 3000);
    }

    private void animateStep() {
        AlphaAnimation aa = new AlphaAnimation(0f, 1f);
        aa.setDuration(180);
        loadStep.startAnimation(aa);
    }

    private void setProgress(int pct) {
        loadPct.setText(pct + "%");
        ViewGroup.LayoutParams lp = loadBar.getLayoutParams();
        View parent = (View) loadBar.getParent();
        if (parent.getWidth() > 0) {
            lp.width = (int) (parent.getWidth() * pct / 100f);
            loadBar.setLayoutParams(lp);
        } else {
            parent.post(() -> {
                ViewGroup.LayoutParams l2 = loadBar.getLayoutParams();
                l2.width = (int) (parent.getWidth() * pct / 100f);
                loadBar.setLayoutParams(l2);
            });
        }
    }

    private void finishLoading() {
        if (loadingDone) return;
        loadingDone = true;
        main.postDelayed(() -> {
            AlphaAnimation aa = new AlphaAnimation(1f, 0f);
            aa.setDuration(400);
            loadingRoot.startAnimation(aa);
            loadingRoot.setVisibility(View.GONE);

            refreshLoginState();
            switchPage("home");
            showNotice();
            // 每次启动检查更新
            UpdateManager.check(this);
        }, 350);
    }

    /* =============== 公告 =============== */

    private void showNotice() {
        // 加载期已经拿到 noticeText，这里直接用（与网页一致：动画结束后才弹）
        String saved = Prefs.get(Prefs.K_NOTICE, "");
        if (TextUtils.isEmpty(noticeText)) return;
        if (noticeText.equals(saved)) return;          // 同一条公告只弹一次

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        View v = getLayoutInflater().inflate(R.layout.dialog_notice, null);
        ((TextView) v.findViewById(R.id.noticeText)).setText(noticeText);
        b.setView(v);
        AlertDialog d = b.create();
        v.findViewById(R.id.noticeOk).setOnClickListener(x -> {
            Prefs.set(Prefs.K_NOTICE, noticeText);
            d.dismiss();
        });
        d.setCancelable(true);
        d.setOnDismissListener(x -> Prefs.set(Prefs.K_NOTICE, noticeText));
        main.postDelayed(() -> { try { d.show(); } catch (Exception ignored) {} }, 500);
    }

    /* =============== 导航 =============== */

    private void setupNav() {
        findViewById(R.id.navHome).setOnClickListener(v -> switchPage("home"));
        findViewById(R.id.navMe).setOnClickListener(v -> switchPage("me"));

        findViewById(R.id.tabHome).setOnClickListener(v -> switchPage("home"));
        findViewById(R.id.tabMonitor).setOnClickListener(v -> switchPage("monitor"));
        findViewById(R.id.tabFeedback).setOnClickListener(v -> switchPage("feedback"));
        findViewById(R.id.tabMore).setOnClickListener(v -> switchPage("more"));
        findViewById(R.id.tabMe).setOnClickListener(v -> {
            if (Prefs.isLogin()) switchPage("me");
            else openLogin();
        });
    }

    /** 切页：从右向左淡入 + 弹性，0.3s（与网页一致） */
    private void switchPage(String p) {
        curPage = p;
        ScrollView target;
        switch (p) {
            case "monitor": target = pageMonitor; break;
            case "feedback": target = pageFeedback; break;
            case "more": target = pageMore; break;
            case "me": target = pageMe; break;
            default: target = pageHome; break;
        }
        ScrollView[] all = {pageHome, pageMonitor, pageFeedback, pageMore, pageMe};
        for (ScrollView s : all) if (s != null) s.setVisibility(View.GONE);
        target.setVisibility(View.VISIBLE);

        // 切到「我的」时隐藏顶部栏目
        topTabs.setVisibility("me".equals(p) ? View.GONE : View.VISIBLE);

        animateIn(target);

        markTab(R.id.navHome, !"me".equals(p));
        markTab(R.id.navMe, "me".equals(p));
        markTab(R.id.tabHome, "home".equals(p));
        markTab(R.id.tabMonitor, "monitor".equals(p));
        markTab(R.id.tabFeedback, "feedback".equals(p));
        markTab(R.id.tabMore, "more".equals(p));

        if ("feedback".equals(p)) loadMyFeedback();
        if ("me".equals(p)) { loadMe(); renderThemes(); renderMeActions(); }
        if ("monitor".equals(p)) { loadMcStatus(); loadApiStatus(); }
    }

    private void markTab(int id, boolean on) {
        TextView t = findViewById(id);
        if (t == null) return;
        t.setBackgroundResource(on ? R.drawable.tab_pill_on : R.drawable.tab_pill);
        t.setTextColor(on ? 0xFF5FD68F : 0xB3EAF6EF);
    }

    private void animateIn(View v) {
        AlphaAnimation aa = new AlphaAnimation(0f, 1f);
        TranslateAnimation ta = new TranslateAnimation(
                Animation.RELATIVE_TO_SELF, 0.12f, Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f);
        AnimationSet set = new AnimationSet(true);
        set.addAnimation(aa);
        set.addAnimation(ta);
        set.setDuration(300);
        set.setInterpolator(new OvershootInterpolator(0.6f));
        v.startAnimation(set);
    }

    private void openLogin() {
        new LoginDialog(this, user -> {
            refreshLoginState();
            if (!TextUtils.isEmpty(user)) {
                switchPage("me");
                toast("欢迎，" + user);
            }
        }).show();
    }

    /* =============== 首页 =============== */

    private void setupHome() {
        rulesText.setText("1. 禁止使用任何外挂 / 作弊客户端\n"
                + "2. 禁止恶意破坏他人建筑\n"
                + "3. 禁止偷窃他人物品\n"
                + "4. 禁止辱骂、刷屏\n"
                + "5. 禁止建造大型卡顿红石机器");
        contactText.setText("服主：ppksp\n管理员：见群内公告\nQQ 群：1021430831");

        findViewById(R.id.btnRefreshStatus).setOnClickListener(v -> loadMcStatus());

        surveyBanner.findViewById(R.id.surveyClose)
                .setOnClickListener(v -> surveyBanner.setVisibility(View.GONE));
        surveyBanner.setOnClickListener(v -> openUrl(SURVEY_URL));

        if (Prefs.isLogin()) checkSurvey();
    }

    private static final String SURVEY_URL = "https://xiaof.hyperphp.com/php/survey.html";

    private void checkSurvey() {
        Api.post("survey_status", null, j -> {
            if (j == null || !j.optBoolean("ok", false)) return;
            boolean open = j.optInt("open", 0) == 1;
            boolean answered = j.optInt("answered", 0) == 1;
            if (open && !answered && Prefs.get(Prefs.K_BANNER_HIDDEN, "0").equals("0")) {
                surveyBanner.setVisibility(View.VISIBLE);
            }
        });
    }

    /* =============== MC 状态 =============== */

    private void loadMcStatus() {
        McStatus.fetch(main, r -> {
            stOnline.setText(r.online ? "在线状态：在线" : "在线状态：离线");
            stOnline.setTextColor(r.online ? 0xFF5FD68F : 0xFFF87171);
            stPlayers.setText("在线人数：" + r.players + " / " + (r.max > 0 ? r.max : 20));
            stVersion.setText("版本：" + (r.version.isEmpty() ? "-" : r.version));
            String motd = (r.motd == null ? "" : r.motd).replace("\n", " ");
            stMotd.setText(motd.isEmpty() ? "" : "MOTD：" + motd);

            motdText.setText("MOTD：" + (motd.isEmpty() ? "-" : motd));
            pingText.setText(r.ping > 0 ? ("延迟：" + r.ping + "ms") : "延迟：-");
            playerCountText.setText("在线：" + r.players + " 人");

            playerList.removeAllViews();
            if (r.playerList.isEmpty()) {
                playerList.addView(line("暂无玩家在线", 0xB3EAF6EF));
            } else {
                for (String n : r.playerList) playerList.addView(line("• " + n, 0xFFEAF6EF));
            }
        });
    }

    /* =============== 监控：接口状态 =============== */

    private void setupMonitor() {
        findViewById(R.id.btnRefresh).setOnClickListener(v -> {
            loadMcStatus();
            loadApiStatus();
        });
        findViewById(R.id.btnRetestApi).setOnClickListener(v -> loadApiStatus());
    }

    private void loadApiStatus() {
        apiList.removeAllViews();
        for (String base : Api.BASES) {
            TextView row = line("● " + Api.shortName(base) + "  测试中…", 0xFFFB923C);
            apiList.addView(row);
            Api.probe(base, (ok, ms, chan) -> {
                row.setText("● " + Api.shortName(base) + "  "
                        + (ok ? ("正常 " + ms + "ms") : "离线")
                        + (ok && !chan.isEmpty() ? ("（" + chan + "）") : ""));
                row.setTextColor(ok ? 0xFF5FD68F : 0xFFF87171);
            });
        }
    }

    /* =============== 反馈 =============== */

    private void setupFeedback() {
        String[] types = {"Bug 报告", "建议", "申诉", "其他"};
        fbType.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, types));

        findViewById(R.id.fbLogin).setOnClickListener(v -> openLogin());
        findViewById(R.id.fbSubmit).setOnClickListener(v -> {
            String content = fbContent.getText().toString().trim();
            if (content.isEmpty()) { toast("请填写反馈内容"); return; }
            Button btn = (Button) v;
            btn.setEnabled(false);
            btn.setText("提交中…");
            String type = String.valueOf(fbType.getSelectedItem());
            Api.postWrite("feedback_add", Api.map("type", type, "content", content), j -> {
                btn.setEnabled(true);
                btn.setText("提交反馈");
                if (j == null) { toast("提交失败：服务器没有响应"); return; }
                if (!j.optBoolean("ok", false)) { toast(j.optString("message", "提交失败")); return; }
                toast("提交成功，感谢反馈！");
                fbContent.setText("");
                loadMyFeedback();
            });
        });
    }

    private void loadMyFeedback() {
        if (!Prefs.isLogin()) {
            fbMine.removeAllViews();
            fbMine.addView(line("登录后可见", 0xB3EAF6EF));
            return;
        }
        Api.post("feedback_mine", null, j -> {
            fbMine.removeAllViews();
            if (j == null || !j.optBoolean("ok", false)) {
                fbMine.addView(line("加载失败", 0xB3EAF6EF));
                return;
            }
            JSONArray arr = j.optJSONArray("list");
            if (arr == null || arr.length() == 0) {
                fbMine.addView(line("还没有提交过反馈", 0xB3EAF6EF));
                return;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String txt = "[" + o.optString("type", "") + "] "
                        + o.optString("content", "").replace("\n", " ");
                if (txt.length() > 90) txt = txt.substring(0, 90) + "…";
                fbMine.addView(line("• " + txt, 0xFFEAF6EF));
                fbMine.addView(line("  " + o.optString("time", ""), 0x88EAF6EF));
            }
        });
    }

    /* =============== 更多 =============== */

    private void setupMore() {
        findViewById(R.id.linkFeedback).setOnClickListener(v -> openUrl("https://xiaof.hyperphp.com/php/feedback.html"));
        findViewById(R.id.linkVote).setOnClickListener(v -> openUrl("https://xiaof.hyperphp.com/t/"));
        findViewById(R.id.linkApp).setOnClickListener(v -> openUrl("https://xiaof.hyperphp.com/php/appdownload.html"));
        loadFaq();
    }

    /** FAQ：从 faq.json 拉，失败用内置兜底 */
    private void loadFaq() {
        new Thread(() -> {
            String text = null;
            for (String base : Api.BASES) {
                text = Api.httpGet(base.replace("/php/api.php", "/faq.json"), "*/*");
                if (text != null && text.trim().startsWith("{")) break;
                text = null;
            }
            final List<String[]> items = new ArrayList<>();
            if (text != null) {
                try {
                    JSONArray arr = new JSONObject(text).optJSONArray("items");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject it = arr.optJSONObject(i);
                            if (it != null) {
                                items.add(new String[]{it.optString("q", ""), it.optString("a", "")});
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            if (items.isEmpty()) {
                items.add(new String[]{"服务器怎么进？", "Minecraft Java 版 1.21.11，地址 mc.xfan.l.cd"});
                items.add(new String[]{"积分有什么用？", "可兑换主题，后续还会开放更多功能"});
            }
            main.post(() -> {
                faqList.removeAllViews();
                for (String[] it : items) {
                    TextView q = line("▾ " + it[0], 0xFFEAF6EF);
                    q.setPadding(0, 10, 0, 6);
                    TextView a = line("    " + it[1], 0xB3EAF6EF);
                    a.setVisibility(View.GONE);
                    faqList.addView(q);
                    faqList.addView(a);
                    q.setOnClickListener(v -> a.setVisibility(
                            a.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
                }
            });
        }).start();
    }

    private void openUrl(String u) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); }
        catch (Exception e) { toast("无法打开链接"); }
    }

    /* =============== 我的 =============== */

    private void setupMe() {
        findViewById(R.id.meLogin).setOnClickListener(v -> openLogin());
        meSign.setOnClickListener(v -> doSign());
    }

    private void doSign() {
        meSign.setEnabled(false);
        meSign.setText("…");
        Api.postWrite("sign", null, j -> {
            if (j == null) { settleSign(); return; }
            if (!j.optBoolean("ok", false)) {
                String m = j.optString("message", "");
                // 「已经签到过」说明其实成功了，只是响应绕了一圈才回来
                if (m.contains("签到")) { signedUi(m); return; }
                meSign.setEnabled(true);
                meSign.setText("签到");
                meMsg.setText(m);
                return;
            }
            signedUi("签到成功，获得 " + j.optInt("gain", 0) + " 积分！");
        });
    }

    /** 签到超时：主动查一次账号确认，不让用户干等 */
    private void settleSign() {
        meSign.setText("…");
        Api.post("me", null, r -> {
            meSign.setEnabled(true);
            meSign.setText("签到");
            if (r == null || !r.optBoolean("ok", false)) {
                meMsg.setText("无法确认签到结果，请稍后再试");
                return;
            }
            JSONObject p = r.optJSONObject("profile");
            String last = (p == null) ? "" : p.optString("last_sign", "");
            if (last.startsWith(today())) {
                signedUi("签到成功！（积分已到账）");
            } else {
                meMsg.setText("确认过了，这次没有签到成功，可以再试一次");
            }
        });
    }

    private void signedUi(String msg) {
        meMsg.setText(msg);
        meSign.setEnabled(false);
        meSign.setText("已签到");
        meHint.setText("今天已签到，明天再来（+10~100 积分）");
        loadMe();
    }

    /** 主题列表：未购买显示「N 积分即可」，已购不显示价格 */
    private void renderThemes() {
        themeList.removeAllViews();
        List<String> owned = Prefs.ownedThemes();
        for (String id : Theme.all()) {
            int price = Theme.price(id);
            boolean isOwned = owned.contains(id) || price == 0;
            // 下架主题：没买过的直接不显示
            if (!Theme.enabled(id) && !isOwned) continue;

            TextView tv = new TextView(this);
            String txt = Theme.name(id) + "  " + Theme.desc(id);
            if (!isOwned) txt += "\n" + price + " 积分即可";
            else if (!Theme.enabled(id)) txt += "\n已下架 · 仅已购用户可用";
            tv.setText(txt);
            tv.setTextColor(Theme.current().equals(id) ? 0xFF5FD68F : 0xFFEAF6EF);
            tv.setTextSize(14);
            tv.setPadding(12, 14, 12, 14);
            tv.setBackgroundResource(Theme.current().equals(id)
                    ? R.drawable.tab_pill_on : R.drawable.tab_pill);

            tv.setOnClickListener(v -> {
                if (isOwned) { applyThemeAnimated(id, false); return; }
                confirmBuy(id, price);
            });
            themeList.addView(tv);
        }
    }

    /** 购买确认：明码标价，后端才真扣积分 */
    private void confirmBuy(String id, int price) {
        int left = curPoints - price;
        new AlertDialog.Builder(this)
                .setTitle("兑换主题")
                .setMessage("兑换「" + Theme.name(id) + "」将扣除 " + price + " 积分。\n"
                        + "当前 " + curPoints + " 分，兑换后剩余 " + Math.max(0, left) + " 分。\n"
                        + "兑换后永久可用，不会再重复扣费。")
                .setPositiveButton("兑换", (d, w) -> buyTheme(id, price))
                .setNegativeButton("取消", null)
                .show();
    }

    private void buyTheme(String id, int price) {
        Api.postWrite("buy_theme", Api.map("theme", id), j -> {
            if (j == null) { toast("服务器没有响应，兑换未完成"); return; }
            if (!j.optBoolean("ok", false)) { toast(j.optString("message", "兑换失败")); return; }
            List<String> owned = Prefs.ownedThemes();
            if (!owned.contains(id)) { owned.add(id); Prefs.setOwnedThemes(owned); }
            JSONObject p = j.optJSONObject("profile");
            if (p != null) curPoints = p.optInt("points", curPoints);
            toast("已兑换「" + Theme.name(id) + "」");
            applyThemeAnimated(id, true);
            renderThemes();
            loadMe();
        });
    }

    /** 切主题：扫屏过渡，遮住屏幕的瞬间才换配色 */
    private void applyThemeAnimated(String id, boolean save) {
        int[] c = Theme.bg(id);
        themeWipe.setBackgroundColor(c[1]);
        themeWipe.setVisibility(View.VISIBLE);
        TranslateAnimation ta = new TranslateAnimation(
                Animation.RELATIVE_TO_SELF, 0, Animation.RELATIVE_TO_SELF, 0,
                Animation.RELATIVE_TO_SELF, -1f, Animation.RELATIVE_TO_SELF, 1f);
        ta.setDuration(720);
        themeWipe.startAnimation(ta);
        main.postDelayed(() -> {
            Theme.apply(findViewById(R.id.root), id);
            Prefs.set(Prefs.K_THEME, id);
            themeWipe.setVisibility(View.GONE);
            renderThemes();
        }, 300);
        if (save && Prefs.isLogin()) {
            Api.postWrite("set_theme", Api.map("theme", id), null);
        }
    }

    /** 账号操作区：退出登录、问卷调查 */
    private void renderMeActions() {
        meActions.removeAllViews();
        if (!Prefs.isLogin()) return;

        String[] names = {"退出登录", "问卷调查"};
        for (int i = 0; i < names.length; i++) {
            Button b = new Button(this);
            b.setText(names[i]);
            b.setTextColor(0xFFEAF6EF);
            b.setTextSize(14);
            b.setBackgroundResource(R.drawable.btn_gray);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 10;
            b.setLayoutParams(lp);
            final int idx = i;
            b.setOnClickListener(v -> {
                if (idx == 0) {
                    Api.postWrite("logout", null, j -> {
                        Prefs.remove(Prefs.K_TOKEN);
                        Prefs.remove(Prefs.K_USER);
                        refreshLoginState();
                        switchPage("home");
                    });
                } else {
                    openUrl(SURVEY_URL);
                }
            });
            meActions.addView(b);
        }
    }

    /* =============== 登录态 =============== */

    private void refreshLoginState() {
        boolean login = Prefs.isLogin();
        meGuest.setVisibility(login ? View.GONE : View.VISIBLE);
        meUser.setVisibility(login ? View.VISIBLE : View.GONE);
        fbLock.setVisibility(login ? View.GONE : View.VISIBLE);
        fbBody.setVisibility(login ? View.VISIBLE : View.GONE);
        TextView tabMe = findViewById(R.id.tabMe);
        if (tabMe != null) {
            tabMe.setText(login ? Prefs.get(Prefs.K_USER, "我的") : "登录 / 注册");
        }
        if (login) { loadMe(); checkSurvey(); }
    }

    private void loadMe() {
        if (!Prefs.isLogin()) return;
        Api.post("me", null, j -> {
            if (j == null || !j.optBoolean("ok", false)) return;
            JSONObject p = j.optJSONObject("profile");
            if (p == null) return;
            meName.setText(p.optString("user", "--"));
            String em = p.optString("email", "");
            meEmail.setText("邮箱：" + (em.isEmpty() ? "无" : em));
            curPoints = p.optInt("points", 0);
            mePoints.setText("积分 " + curPoints);

            boolean signed = p.optString("last_sign", "").startsWith(today());
            meSign.setEnabled(!signed);
            meSign.setText(signed ? "已签到" : "签到");
            meHint.setText(signed ? "今天已签到，明天再来（+10~100 积分）"
                    : "今日未签到，可获得 10~100 随机积分");
        });
    }

    private String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
    }

    /* =============== 工具 =============== */

    private TextView line(String text, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(13);
        tv.setPadding(0, 4, 0, 4);
        return tv;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
