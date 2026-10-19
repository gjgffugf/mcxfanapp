package xfan.mc.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 装饰层：复刻网页的两组效果
 * <ol>
 *   <li><b>漂浮物</b>：背景里缓慢上浮的小光点（网页 float-layer）</li>
 *   <li><b>反重力粒子环</b>：指针周围粒子聚成环，内圈留空，
 *       带一个磨砂跟随圈（网页 Astro antigravity 效果）</li>
 * </ol>
 *
 * 桌面端跟随鼠标，触屏跟随手指；点击/抬起会平滑过渡到该位置。
 */
public class DecorView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();

    /* ---- 漂浮物 ---- */
    private final List<Float> fx = new ArrayList<>();
    private final List<Float> fy = new ArrayList<>();
    private final List<Float> fv = new ArrayList<>();
    private final List<Float> fr = new ArrayList<>();
    private static final int FLOAT_N = 26;
    private boolean floatInit = false;

    /* ---- 粒子环 ---- */
    private static final int RING_N = 110;
    private final float[] rx = new float[RING_N];
    private final float[] ry = new float[RING_N];
    private final float[] rz = new float[RING_N];       // 深度，用于视差
    private final float[] rr = new float[RING_N];       // 半径倍率
    private final float[] ra = new float[RING_N];       // 角度
    private boolean ringInit = false;

    /* 指针位置：目标值 + 当前值（lerp 缓动） */
    private float targetX = -1, targetY = -1;
    private float curX = -1, curY = -1;
    private static final float EASE = 0.26f;
    private float appear = 0f;          // 渐显
    private boolean active = false;
    private boolean firstSeen = false;

    /* 屏幕高度缩放：原版参数按「屏高 1/3」为环半径 */
    private float S;

    public DecorView(Context c) { super(c); }
    public DecorView(Context c, AttributeSet a) { super(c, a); }
    public DecorView(Context c, AttributeSet a, int s) { super(c, a, s); }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        S = h / 31.5f;              // 与网页一致的换算：原版 ringRadius 10 ≈ 屏高 1/3
        initFloats();
        initRing();
        // 指针目标按新尺寸换算，避免转屏后跑偏
        if (targetX >= 0 && oldw > 0 && oldh > 0) {
            targetX = targetX / oldw * w;
            targetY = targetY / oldh * h;
        }
    }

    private void initFloats() {
        fx.clear(); fy.clear(); fv.clear(); fr.clear();
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        for (int i = 0; i < FLOAT_N; i++) {
            fx.add(rnd.nextFloat() * w);
            fy.add(rnd.nextFloat() * h);
            fv.add(0.25f + rnd.nextFloat() * 0.55f);
            fr.add(1.2f + rnd.nextFloat() * 2.6f);
        }
        floatInit = true;
    }

    private void initRing() {
        for (int i = 0; i < RING_N; i++) {
            rz[i] = 0.35f + rnd.nextFloat() * 0.65f;      // 深度
            rr[i] = 0.75f + rnd.nextFloat() * 0.5f;       // 半径抖动
            ra[i] = rnd.nextFloat() * (float) (Math.PI * 2);
            rx[i] = 0; ry[i] = 0;
        }
        ringInit = true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                active = true;
                setTarget(e.getX(), e.getY());
                break;
            case MotionEvent.ACTION_MOVE:
                setTarget(e.getX(), e.getY());
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                // 抬起后延迟淡出，粒子自然消散
                postDelayed(() -> active = false, 1500);
                break;
        }
        return true;    // 消费掉，不影响下方滚动
    }

    private void setTarget(float x, float y) {
        targetX = x;
        targetY = y;
        if (!firstSeen) { curX = x; curY = y; firstSeen = true; }
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        drawFloats(c);
        drawRing(c);
        postInvalidateOnAnimation();     // 持续动画
    }

    /* ---- 漂浮物：缓慢上浮，出顶后从底部回绕 ---- */
    private void drawFloats(Canvas c) {
        if (!floatInit) return;
        int w = getWidth(), h = getHeight();
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < fx.size(); i++) {
            float y = fy.get(i) - fv.get(i);
            if (y < -8) { y = h + 8; fx.set(i, rnd.nextFloat() * w); }
            fy.set(i, y);
            p.setColor(0x2A5FD68F);
            c.drawCircle(fx.get(i), y, fr.get(i), p);
        }
    }

    /* ---- 反重力粒子环 ---- */
    private void drawRing(Canvas c) {
        if (!ringInit || S <= 0) return;

        if (active) appear = Math.min(1f, appear + 0.12f);
        else appear = Math.max(0f, appear - 0.04f);
        if (appear <= 0.01f) return;

        // 指针位置缓动
        if (targetX >= 0) {
            if (curX < 0) { curX = targetX; curY = targetY; }
            curX += (targetX - curX) * EASE;
            curY += (targetY - curY) * EASE;
        } else return;

        float ringR = S * 10 * 0.55f;      // 环半径，比原版略收一点适配手机

        // 磨砂跟随圈
        p.setStyle(Paint.Style.FILL);
        p.setColor((int) (0x22FFFFFF * appear));
        c.drawCircle(curX, curY, ringR * 0.72f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.6f);
        p.setColor((int) (0x33FFFFFF * appear));
        c.drawCircle(curX, curY, ringR * 0.72f, p);

        // 粒子：绕环分布，内圈 60% 留空
        p.setStyle(Paint.Style.FILL);
        long t = System.currentTimeMillis();
        for (int i = 0; i < RING_N; i++) {
            ra[i] += 0.0016f * rz[i];
            float rad = ringR * rr[i] * (0.62f + 0.38f * rz[i]);
            // 深度视差：不同 z 的环心略有偏移
            float px = curX + (float) Math.cos(ra[i]) * rad * rz[i];
            float py = curY + (float) Math.sin(ra[i]) * rad * rz[i];
            float sz = (0.9f + rz[i] * 1.7f);
            int alpha = (int) (200 * rz[i] * appear);
            // 青白交替
            p.setColor((i % 3 == 0) ? ((alpha << 24) | 0x9FE8FF)
                                    : ((alpha << 24) | 0x5FD68F));
            c.drawCircle(px, py, sz, p);
        }
        // 噪点：轻微压住塑料感
        p.setColor((int) (0x08FFFFFF * appear));
        for (int i = 0; i < 18; i++) {
            c.drawCircle(curX + (rnd.nextFloat() - 0.5f) * ringR * 2,
                         curY + (rnd.nextFloat() - 0.5f) * ringR * 2,
                         1.1f, p);
        }
        p.setStyle(Paint.Style.FILL);
    }
}
