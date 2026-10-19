package xfan.mc.app;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Map;

/**
 * 登录弹窗：复刻网页版登录框。
 *
 * 三 tab（登录 / 注册 / 找回）、游客模式、接口状态灯，
 * 请求走四个接口源 × CSS/JSONP/GET/POST 四种通道，逻辑与网页一致。
 *
 * 登录限流：连续输错 5 次锁 60 秒（存在本地，与网页的浏览器缓存对应）。
 */
public class LoginDialog extends AlertDialog {

    public interface OnLogin { void onOk(String user); }

    private final OnLogin cb;
    private boolean modeLogin = true;

    private EditText etUser, etPwd, etEmail;
    private TextView tvMsg, tabLogin, tabReg;
    private LinearLayout apiList;
    private Button go;

    /* 限流 */
    private static final int MAX_FAIL = 5;
    private static final long LOCK_MS = 60_000;
    private static final String K_FAILS = "login_fails";
    private static final String K_LOCK = "login_lock_at";

    public LoginDialog(Context c, OnLogin cb) {
        super(c);
        this.cb = cb;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.dialog_login);

        etUser = findViewById(R.id.lgUser);
        etPwd = findViewById(R.id.lgPwd);
        etEmail = findViewById(R.id.lgEmail);
        tvMsg = findViewById(R.id.lgMsg);
        apiList = findViewById(R.id.lgApiList);
        go = findViewById(R.id.lgGo);
        tabLogin = findViewById(R.id.lgTabLogin);
        tabReg = findViewById(R.id.lgTabReg);
        TextView tabFind = findViewById(R.id.lgTabFind);

        tabLogin.setOnClickListener(v -> setMode(true));
        tabReg.setOnClickListener(v -> setMode(false));
        tabFind.setOnClickListener(v -> msg("忘记密码请联系管理员重置：QQ 群 1021430831"));

        go.setOnClickListener(v -> submit());
        findViewById(R.id.lgGuest).setOnClickListener(v -> {
            dismiss();
            if (cb != null) cb.onOk("");
        });
        findViewById(R.id.lgRetest).setOnClickListener(v -> testApis());

        setMode(true);
        testApis();
    }

    private void setMode(boolean login) {
        modeLogin = login;
        tabLogin.setBackgroundResource(login ? R.drawable.tab_pill_on : R.drawable.tab_pill);
        tabReg.setBackgroundResource(login ? R.drawable.tab_pill : R.drawable.tab_pill_on);
        tabLogin.setTextColor(login ? 0xFF5FD68F : 0xB3EAF6EF);
        tabReg.setTextColor(login ? 0xB3EAF6EF : 0xFF5FD68F);
        etEmail.setVisibility(login ? View.GONE : View.VISIBLE);
        go.setText(login ? "登录" : "注册并登录");
        msg("");
    }

    private void msg(String s) {
        tvMsg.setText(s == null ? "" : s);
    }

    /* ---- 接口状态灯：四个源逐个探测 ---- */
    private void testApis() {
        apiList.removeAllViews();
        for (String base : Api.BASES) {
            TextView row = new TextView(getContext());
            row.setText("● " + Api.shortName(base) + "  测试中…");
            row.setTextColor(0xFFFB923C);
            row.setTextSize(12.5f);
            row.setPadding(0, 6, 0, 6);
            apiList.addView(row);

            Api.probe(base, (ok, ms, chan) -> {
                row.setText("● " + Api.shortName(base) + "  "
                        + (ok ? ("正常 " + ms + "ms") : "离线")
                        + (ok && !chan.isEmpty() ? ("（" + chan + "）") : ""));
                row.setTextColor(ok ? 0xFF5FD68F : 0xFFF87171);
            });
        }
    }

    private void submit() {
        String user = etUser.getText().toString().trim();
        String pwd = etPwd.getText().toString();
        if (user.isEmpty() || pwd.isEmpty()) { msg("请填写用户名和密码"); return; }

        // 限流检查
        long lockAt = Long.parseLong(Prefs.get(K_LOCK, "0"));
        if (lockAt > 0 && System.currentTimeMillis() - lockAt < LOCK_MS) {
            long left = (LOCK_MS - (System.currentTimeMillis() - lockAt)) / 1000;
            msg("尝试次数过多，请 " + left + " 秒后再试");
            return;
        }

        go.setEnabled(false);
        go.setText(modeLogin ? "登录中…" : "注册中…");
        msg("");

        String action = modeLogin ? "login" : "register";
        Map<String, String> d = Api.map("user", user, "password", pwd);
        if (!modeLogin && !TextUtils.isEmpty(etEmail.getText().toString().trim())) {
            d.put("email", etEmail.getText().toString().trim());
        }

        Api.postWrite(action, d, j -> {
            go.setEnabled(true);
            go.setText(modeLogin ? "登录" : "注册并登录");

            if (j == null) {
                // 完全没拿到响应 = 网络/WAF 层失败，不算密码错误，不计数
                msg("服务器连不上（已自动重试多次），请稍后再试");
                return;
            }
            if (!j.optBoolean("ok", false)) {
                msg(j.optString("message", "操作失败"));
                if (modeLogin) countFail();
                return;
            }
            String token = j.optString("token", "");
            if (token.isEmpty()) { msg("登录失败：没有拿到令牌"); return; }

            Prefs.set(Prefs.K_TOKEN, token);
            JSONObject p = j.optJSONObject("profile");
            String name = (p == null) ? user : p.optString("user", user);
            Prefs.set(Prefs.K_USER, name);
            clearFail();

            dismiss();
            if (cb != null) cb.onOk(name);
        });
    }

    private void countFail() {
        int n = Integer.parseInt(Prefs.get(K_FAILS, "0")) + 1;
        Prefs.set(K_FAILS, String.valueOf(n));
        if (n >= MAX_FAIL) {
            Prefs.set(K_LOCK, String.valueOf(System.currentTimeMillis()));
            Prefs.set(K_FAILS, "0");
            msg("连续输错 " + MAX_FAIL + " 次，请 60 秒后再试");
        }
    }

    private void clearFail() {
        Prefs.set(K_FAILS, "0");
        Prefs.set(K_LOCK, "0");
    }
}
