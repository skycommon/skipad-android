package com.lbx.skipad;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 开屏广告跳过服务（v2）。
 *
 * v1 的主要缺陷：只用 getRootInActiveWindow() 扫「当前活动窗口」。
 * 但开屏广告经常由广告 SDK 渲染在**独立窗口**（甚至多个窗口）里，
 * 不在活动窗口的节点树内，于是永远扫不到 —— 这就是 QQ音乐 之类跳不掉的原因。
 * v2 改用 getWindows() 遍历全部窗口。
 *
 * 其它 v2 改进：
 *  - 候选打分挑最优，而不是命中第一个就点；
 *  - 点完不复位观察窗口，持续到超时，能处理「广告分两段」的情况；
 *  - 未命中且开启诊断时，抓一份窗口树摘要存本地，便于精确定位规则。
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
    /** 是否已成功跳过一次（continueAfterSkip=false 时用于提前收工）。 */
    private boolean skippedOnce = false;
    private long lastScan = 0L;
    private boolean scanScheduled = false;
    /** 本次观察里是否已经做过盲点兜底，避免重复盲点。 */
    private boolean blindTried = false;
    /** 屏幕尺寸缓存。 */
    private int screenW = 0;
    private int screenH = 0;

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
        String t = "监听中：" + s.watchPkg;
        if (s.lastScanReason != null) t += "\n最近扫描：" + s.lastScanReason;
        return t;
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        reloadRules();
        updateScreenSize();
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

        if (!RuleStore.isMasterEnabled(this)) return;

        RuleStore.Rules r = rules;
        if (r == null) {
            reloadRules();
            r = rules;
            if (r == null) return;
        }

        final int type = event.getEventType();
        String pkg = event.getPackageName() == null ? null : event.getPackageName().toString();

        final long now = android.os.SystemClock.uptimeMillis();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null) {
            onForegroundChanged(pkg, now);
        }

        if (watchPkg == null) return;
        if (now > watchDeadline) {
            endWatch(false);
            return;
        }
        // 已经点掉过一次就收工（除非用户要求继续）
        if (skippedOnce && !r.continueAfterSkip) return;
        if (attempts >= r.retryLimit) return;

        // 广告窗口可能来自别的包名（广告 SDK 独立进程），这里不再按包名过滤，
        // 只依赖「是否处于观察窗口」这一层限制。
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            scheduleScan(now);
        }
    }

    // ------------------------------------------------------------------ 观察窗口

    private void onForegroundChanged(String pkg, long now) {
        reloadRules();

        if (pkg.equals(watchPkg)) {
            // 同一应用内的界面切换，不重置窗口
            return;
        }

        if (watchPkg != null) endWatch(false);

        RuleStore.Rules r = rules;
        if (r == null || !r.shouldWatch(pkg)) return;

        watchPkg = pkg;
        watchDeadline = now + r.maxWaitMs;
        attempts = 0;
        skippedOnce = false;
        blindTried = false;
        lastScan = 0L;
        lastScanReason = null;
        updateScreenSize();

        scheduleScan(now);
        Log.i(TAG, "开始监听 " + pkg);
    }

    private void endWatch(boolean timedOut) {
        // 超时且一直没命中，且用户开了诊断，就抓一份窗口树快照
        if (timedOut && !skippedOnce && RuleStore.isDiagnose(this) && watchPkg != null) {
            captureDiagnostic(watchPkg);
        }
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

    private void updateScreenSize() {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 扫描与点击

    /** 上一次扫描的简述，用于诊断。 */
    private String lastScanReason = null;

    private void doScan() {
        final RuleStore.Rules r = rules;
        if (r == null) return;
        if (watchPkg == null) return;

        long now = android.os.SystemClock.uptimeMillis();
        if (now > watchDeadline) {
            endWatch(true);
            return;
        }
        if (skippedOnce && !r.continueAfterSkip) {
            endWatch(false);
            return;
        }
        lastScan = now;

        // ---- 收集所有可见窗口的根节点（v2 的核心改动） ----
        List<AccessibilityNodeInfo> roots = new ArrayList<AccessibilityNodeInfo>();
        List<int[]> sizes = new ArrayList<int[]>();
        StringBuilder winInfo = new StringBuilder();

        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (AccessibilityWindowInfo w : wins) {
                    if (w == null) continue;
                    AccessibilityNodeInfo rt = null;
                    try {
                        rt = w.getRoot();
                    } catch (Throwable ignored) {
                    }
                    if (rt == null) continue;

                    Rect b = new Rect();
                    try {
                        w.getBoundsInScreen(b);
                    } catch (Throwable ignored) {
                    }
                    int cw = b.width() > 0 ? b.width() : screenW;
                    int ch = b.height() > 0 ? b.height() : screenH;

                    roots.add(rt);
                    sizes.add(new int[]{cw, ch});
                    if (winInfo.length() > 0) winInfo.append(", ");
                    winInfo.append("type=").append(w.getType())
                           .append("/layer=").append(w.getLayer())
                           .append("/").append(cw).append('x').append(ch);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "获取窗口列表失败", t);
        }

        // 兜底：getWindows() 拿不到时退回活动窗口
        if (roots.isEmpty()) {
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    roots.add(root);
                    sizes.add(new int[]{screenW, screenH});
                    winInfo.append("activeOnly");
                }
            } catch (Throwable ignored) {
            }
        }
        if (roots.isEmpty()) return;

        SkipEngine.Cand best;
        try {
            best = SkipEngine.findBest(roots, sizes, r, watchPkg);
        } catch (Throwable t) {
            Log.e(TAG, "扫描异常", t);
            return;
        }

        if (best == null) {
            lastScanReason = "未命中（窗口: " + winInfo + "）";
            // 盲点兜底：实在找不到候选时，按用户设定盲点一次
            if (!blindTried && r.blindTapEnabled && attempts < r.retryLimit) {
                blindTried = true;
                attempts++;
                int w = screenW;
                int h = screenH;
                boolean ok = SkipEngine.tapNormalized(this, w, h, r.blindTapX, r.blindTapY);
                if (ok) {
                    lastScanReason = "盲点兜底 " + r.blindTapX + "," + r.blindTapY;
                }
            }
            return;
        }

        attempts++;
        boolean ok;
        try {
            ok = SkipEngine.clickNode(this, best.node);
        } catch (Throwable t) {
            ok = false;
        }

        if (ok) {
            skippedOnce = true;
            onSkipped(watchPkg, best.reason + " · 分数" + best.score());
            if (!r.continueAfterSkip) endWatch(false);
        } else {
            lastScanReason = "命中但点击失败：" + best.reason;
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

    // ------------------------------------------------------------------ 诊断快照

    /**
     * 抓一份当前所有可见窗口的文字节点摘要，存到诊断记录里。
     * 用途：某个 App 跳不掉时，把它的开屏界面文字全部记下来，
     * 用户照着把真正的按钮文字加进 skipTexts 即可。
     */
    private void captureDiagnostic(String pkg) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("包名: ").append(pkg).append('\n');
            sb.append("时间: ").append(
                    new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date())).append('\n');

            List<AccessibilityWindowInfo> wins = getWindows();
            int idx = 0;
            if (wins != null) {
                for (AccessibilityWindowInfo w : wins) {
                    AccessibilityNodeInfo rt = null;
                    try {
                        rt = w.getRoot();
                    } catch (Throwable ignored) {
                    }
                    if (rt == null) continue;
                    idx++;
                    sb.append("\n--- 窗口").append(idx)
                      .append(" (type=").append(w.getType())
                      .append(", layer=").append(w.getLayer()).append(") ---\n");
                    collectForDiag(rt, sb, 0, new int[]{120});
                }
            }
            if (idx == 0) sb.append("（未获取到任何窗口树）\n");
        } catch (Throwable t) {
            sb.append("诊断失败: ").append(t).append('\n');
        }
        appendDiag(sb.toString());
    }

    private void collectForDiag(AccessibilityNodeInfo n, StringBuilder sb, int depth, int[] budget) {
        if (n == null || budget[0] <= 0) return;
        budget[0]--;

        String txt = SkipEngine.norm(n.getText());
        String desc = SkipEngine.norm(n.getContentDescription());
        String id = null;
        try {
            id = n.getViewIdResourceName();
        } catch (Throwable ignored) {
        }

        if (!txt.isEmpty() || !desc.isEmpty() || id != null) {
            Rect r = new Rect();
            try {
                n.getBoundsInScreen(r);
            } catch (Throwable ignored) {
            }
            sb.append("  ".repeat(Math.min(depth, 12)));
            if (id != null) sb.append('#').append(SkipEngine.shortId(id)).append(' ');
            if (!txt.isEmpty()) sb.append('"').append(txt).append('"');
            else if (!desc.isEmpty()) sb.append("(desc)\"").append(desc).append('"');
            try {
                if (n.isClickable()) sb.append(" [可点]");
            } catch (Throwable ignored) {
            }
            if (r.width() > 0) {
                sb.append(' ').append(r.width()).append('x').append(r.height());
            }
            sb.append('\n');
        }

        int c = 0;
        try {
            c = n.getChildCount();
        } catch (Throwable ignored) {
        }
        for (int i = 0; i < c; i++) {
            AccessibilityNodeInfo ch = null;
            try {
                ch = n.getChild(i);
            } catch (Throwable ignored) {
            }
            if (ch != null) collectForDiag(ch, sb, depth + 1, budget);
        }
    }

    private void appendDiag(String text) {
        try {
            JSONArray arr;
            String raw = RuleStore.prefs(this).getString(RuleStore.K_DIAG, null);
            if (TextUtils.isEmpty(raw)) arr = new JSONArray();
            else {
                try {
                    arr = new JSONArray(raw);
                } catch (Throwable t) {
                    arr = new JSONArray();
                }
            }
            JSONArray out = new JSONArray();
            out.put(text);
            for (int i = 0; i < arr.length() && out.length() < 5; i++) out.put(arr.get(i));
            RuleStore.prefs(this).edit().putString(RuleStore.K_DIAG, out.toString()).apply();
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
