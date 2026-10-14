package com.lbx.skipad;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 开屏广告跳过服务。
 *
 * 工作方式：
 *  1. 前台应用发生切换时，若该应用在监听范围内，开启一个“观察窗口”（默认 15 秒）；
 *  2. 观察窗口内，每次窗口内容变化都扫描一次当前窗口树，寻找跳过按钮；
 *  3. 命中则点击，并把“已跳过/已等待”记入日志。
 *
 * 观察窗口是安全设计的关键：开屏广告只可能出现在应用启动的最初几秒，
 * 过了这个窗口就不再扫描，避免在正常使用界面里误点。
 */
public class SkipAccessibilityService extends AccessibilityService {

    private static final String TAG = "SkipAd";
    private static final int LOG_MAX = 60;

    /** 供界面查询服务是否已连接。 */
    private static volatile SkipAccessibilityService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private RuleStore.Rules rules;

    // 当前观察窗口状态
    private String watchPkg = null;
    private long watchDeadline = 0L;
    /** 本窗口内已经尝试点击的次数，超过 retryLimit 就停止，避免反复误点。 */
    private int attempts = 0;
    private boolean skippedOnce = false;
    private long lastScan = 0L;
    private boolean scanScheduled = false;

    private final Runnable scanTask = new Runnable() {
        @Override
        public void run() {
            scanScheduled = false;
            doScan();
        }
    };

    public static boolean isRunning() {
        return instance != null;
    }

    public static String statusText() {
        SkipAccessibilityService s = instance;
        if (s == null) return "未连接";
        if (s.watchPkg == null) return "待命中";
        return "监听中：" + s.watchPkg;
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        reloadRules();
        Log.i(TAG, "服务已连接");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        handler.removeCallbacks(scanTask);
        scanScheduled = false;
        Log.i(TAG, "服务已断开");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacks(scanTask);
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        // 总开关关闭时完全不工作
        if (!RuleStore.isMasterEnabled(this)) return;

        RuleStore.Rules r = rules;
        if (r == null) {
            reloadRules();
            r = rules;
            if (r == null) return;
        }

        final int type = event.getEventType();
        String pkg = event.getPackageName() == null ? null : event.getPackageName().toString();
        if (pkg == null) return;

        final long now = android.os.SystemClock.uptimeMillis();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            onForegroundChanged(pkg, now);
        }

        // 只有在观察窗口内才扫描
        if (watchPkg == null || now > watchDeadline) {
            if (watchPkg != null) endWatch();
            return;
        }
        if (!pkg.equals(watchPkg)) return;
        // 已经点掉过一次就收工（除非用户要求继续）
        if (skippedOnce && !r.continueAfterSkip) return;
        if (attempts >= r.retryLimit) return;

        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            scheduleScan(now);
        }
    }

    // ------------------------------------------------------------------ 观察窗口

    private void onForegroundChanged(String pkg, long now) {
        // 规则可能被用户改过，进入新应用时顺手刷新一次，避免规则已删除仍按旧规则点击
        reloadRules();

        if (pkg.equals(watchPkg)) {
            // 同一应用内的界面切换，不重置窗口
            return;
        }

        if (watchPkg != null) endWatch();

        RuleStore.Rules r = rules;
        if (r == null || !r.shouldWatch(pkg)) return;

        watchPkg = pkg;
        watchDeadline = now + r.maxWaitMs;
        attempts = 0;
        skippedOnce = false;
        lastScan = 0L;

        // 立刻试一次，不必等第一个内容变化事件
        scheduleScan(now);
        Log.i(TAG, "开始监听 " + pkg);
    }

    private void endWatch() {
        watchPkg = null;
        handler.removeCallbacks(scanTask);
        scanScheduled = false;
    }

    private void scheduleScan(long now) {
        RuleStore.Rules r = rules;
        if (r == null || watchPkg == null) return;
        if (scanScheduled) return;
        long since = now - lastScan;
        long delay = since >= r.scanIntervalMs ? 0 : (r.scanIntervalMs - since);
        scanScheduled = true;
        handler.postDelayed(scanTask, delay);
    }

    private void reloadRules() {
        try {
            rules = RuleStore.load(this);
        } catch (Throwable t) {
            Log.e(TAG, "规则加载失败", t);
        }
    }

    // ------------------------------------------------------------------ 扫描与点击

    private void doScan() {
        final RuleStore.Rules r = rules;
        if (r == null) return;
        if (watchPkg == null) return;

        long now = android.os.SystemClock.uptimeMillis();
        if (now > watchDeadline) {
            endWatch();
            return;
        }
        if (skippedOnce && !r.continueAfterSkip) {
            endWatch();
            return;
        }
        lastScan = now;

        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable t) {
            return;
        }
        if (root == null) return;

        SkipEngine.Hit hit;
        try {
            hit = SkipEngine.find(root, r, watchPkg);
        } catch (Throwable t) {
            Log.e(TAG, "扫描异常", t);
            return;
        }
        if (hit == null) return;

        attempts++;
        boolean ok;
        try {
            ok = SkipEngine.clickNode(this, hit.node);
        } catch (Throwable t) {
            ok = false;
        }

        if (ok) {
            skippedOnce = true;
            onSkipped(watchPkg, hit.reason);
            if (!r.continueAfterSkip) endWatch();
        }
    }

    private void onSkipped(String pkg, String reason) {
        String app = rules != null ? rules.nameOf(pkg) : pkg;
        Log.i(TAG, "已跳过 " + app + " / " + reason);

        if (RuleStore.isToast(this)) {
            try {
                Toast.makeText(this, "已跳过广告 · " + app, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }
        if (RuleStore.isVibrate(this)) {
            vibrate();
        }
        appendLog(app, reason);
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(18, 60));
            } else {
                v.vibrate(18);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 日志

    private void appendLog(String app, String reason) {
        try {
            JSONArray arr;
            String raw = RuleStore.prefs(this).getString(RuleStore.K_LOG, null);
            if (TextUtils.isEmpty(raw)) {
                arr = new JSONArray();
            } else {
                try {
                    arr = new JSONArray(raw);
                } catch (Throwable t) {
                    arr = new JSONArray();
                }
            }
            JSONObject o = new JSONObject();
            o.put("t", new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date()));
            o.put("app", app);
            o.put("why", reason);

            JSONArray out = new JSONArray();
            out.put(o);
            for (int i = 0; i < arr.length() && out.length() < LOG_MAX; i++) {
                out.put(arr.get(i));
            }
            RuleStore.prefs(this).edit().putString(RuleStore.K_LOG, out.toString()).apply();
        } catch (Throwable ignored) {
        }
    }
}
