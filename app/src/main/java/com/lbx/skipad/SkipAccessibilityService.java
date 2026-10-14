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
    private long watchStart = 0L;
    private long watchDeadline = 0L;
    /** 本窗口内已经尝试点击的次数，超过 retryLimit 就停止，避免反复误点。 */
    private int attempts = 0;
    /** 是否已成功跳过一次（continueAfterSkip=false 时用于提前收工）。 */
    private boolean skippedOnce = false;
    private long lastScan = 0L;
    private boolean scanScheduled = false;
    /** 本次观察里是否已经做过盲点兜底，避免重复盲点。 */
    private boolean blindTried = false;
    /**
     * 本次观察里是否已经抓过「开屏首批界面」快照。
     *
     * 为什么要在第一次扫描时就抓：开屏广告通常只显示 3~5 秒，
     * 而观察窗口是 15 秒 —— 等到超时才抓，广告早就消失了，
     * 抓到的只是 App 主界面，对排查毫无帮助。
     */
    private boolean diagCapturedEarly = false;
    /** 是否已排入一次主动轮询。 */
    private boolean pollScheduled = false;

    // ---------------- v3.0 防误触状态 ----------------

    /** 上一次认定的前台应用包名，用于区分「真切换应用」和「应用内换界面」。 */
    private String lastFgPkg = null;
    /** 上一次观察的是哪个应用。 */
    private String lastEpisodePkg = null;
    /** 上一次观察结束的时刻（uptimeMillis）。 */
    private long lastEpisodeEndMs = 0L;
    /** 自上次观察结束后，是否出现过系统窗口（桌面/设置等）——说明用户真的离开过。 */
    private boolean sawSystemSinceEpisode = false;

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

    /**
     * 主动轮询任务。
     *
     * 为什么需要它（「有时能跳、有时不行」的根因之二）：
     * 原实现完全靠无障碍事件驱动 —— 只有收到 window 内容变化事件才扫描。
     * 但广告若是纯图片 / 视频 / SurfaceView，渲染时可能**一个事件都不发**，
     * 或者事件在广告出现的瞬间就发完了；这时我们就只扫到「广告还没出来」或
     * 「广告已经变了但没通知」的那一帧，于是漏掉。
     * 加了这个定时器后，观察窗口内会按 pollIntervalMs 周期主动扫描，
     * 不依赖任何事件，是否命中就只取决于广告在不在屏幕上。
     */
    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            pollScheduled = false;
            if (watchPkg == null) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (now > watchDeadline) {
                endWatch(true);
                return;
            }
            // 不在这里判断 skippedOnce：doScan() 内部已经处理了
            // （点掉过就 endWatch(false) 收工），交给它更不容易漏掉善后。
            doScan();
            // doScan 可能已经 endWatch（命中或超时），那就不要再排下一次了
            if (watchPkg != null) schedulePoll();
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
        handler.removeCallbacks(pollTask);
        scanScheduled = false;
        pollScheduled = false;
        Log.i(TAG, "服务已断开");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacks(scanTask);
        handler.removeCallbacks(pollTask);
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
            // 超时收工：这里也算「自然超时」，要让 endWatch 有机会抓诊断快照。
            // （早先传 false 会导致只要由事件路径触发超时，诊断就不会被抓。）
            endWatch(true);
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

        RuleStore.Rules r = rules;
        if (r == null) return;

        // 决策已抽成纯函数（RuleStore.Rules.decideForeground），便于单测覆盖。
        // v3.0 的两个防误触要点都体现在这里：
        //   · 观察一旦开始就**锚定**在原应用上，任何其它包名（含广告 SDK 窗口）
        //     都不接管、不重启 —— 避免把观察窗口拖到正常界面上；
        //   · 区分「真切换应用」与「同应用内换界面」，后者在没在观察时一律忽略
        //     —— 避免开完全屏搜索又被重新扫一轮。
        RuleStore.Rules.FgContext ctx = new RuleStore.Rules.FgContext();
        ctx.watchingPkg = watchPkg;
        ctx.lastFgPkg = lastFgPkg;
        ctx.lastEpisodePkg = lastEpisodePkg;
        ctx.lastEpisodeEndMs = lastEpisodeEndMs;
        ctx.sawSystemSinceEpisode = sawSystemSinceEpisode;
        ctx.now = now;

        int action = r.decideForeground(pkg, ctx);

        // 记录「见过系统窗口」，供冷却判断使用（用户回过桌面=确实离开过该应用）
        if (!pkg.equals(watchPkg) && !r.shouldWatch(pkg)) {
            sawSystemSinceEpisode = true;
        }
        // 更新前台应用记录（在决策之后，因为决策需要用到旧的 lastFgPkg）
        lastFgPkg = pkg;

        if (action == RuleStore.Rules.FG_SAME || action == RuleStore.Rules.FG_IGNORE
                || action == RuleStore.Rules.FG_KEEP) {
            return;
        }

        if (action == RuleStore.Rules.FG_SWITCH && watchPkg != null) {
            endWatch(false);
        }

        watchPkg = pkg;
        watchStart = now;
        // 观察窗口取「开屏期」和用户配置的 maxWaitMs 中较小者：
        // 开屏广告只可能出现在应用刚起来的这一小段时间内。
        long limit = Math.min(r.maxWaitMs, r.safeMode ? r.splashWindowMs : r.maxWaitMs);
        watchDeadline = now + limit;
        attempts = 0;
        skippedOnce = false;
        blindTried = false;
        diagCapturedEarly = false;
        // 新一轮观察开始，重置「期间是否回过系统界面」的标记。
        // 这个标记是给**本轮结束后**的冷却判断用的：必须在这里清掉，
        // 否则一旦用户回过桌面它就永远是 true，冷却逻辑将永久失效
        // （表现为同一应用内换界面又会重开观察 —— 正是要修的误触）。
        sawSystemSinceEpisode = false;
        lastScan = 0L;
        lastScanReason = null;
        updateScreenSize();

        scheduleScan(now);
        schedulePoll();
        Log.i(TAG, "开始监听 " + pkg);
    }

    private void endWatch(boolean timedOut) {
        // 超时且一直没命中，且还没在早期抓过快照时，补抓一份。
        // （正常情况下 doScan 早已在广告还在屏幕上时抓过了；
        //   这里是兜底，比如界面一直没内容变化事件、早期那几秒没扫到。）
        if (timedOut && !skippedOnce && !diagCapturedEarly
                && RuleStore.isDiagnose(this) && watchPkg != null) {
            diagCapturedEarly = true;
            captureDiagnostic(watchPkg, false);
        }
        // 记下这次观察是针对哪个应用、何时结束的，供冷却判断使用
        // （防止同一应用内很快又被重新开一轮观察，从而在正常界面上乱找按钮）。
        if (watchPkg != null) {
            lastEpisodePkg = watchPkg;
            lastEpisodeEndMs = android.os.SystemClock.uptimeMillis();
        }
        watchPkg = null;
        handler.removeCallbacks(scanTask);
        handler.removeCallbacks(pollTask);
        scanScheduled = false;
        pollScheduled = false;
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

    /** 排入下一次主动轮询（与事件驱动的 scheduleScan 互补）。 */
    private void schedulePoll() {
        RuleStore.Rules r = rules;
        if (r == null || watchPkg == null) return;
        if (pollScheduled) return;
        pollScheduled = true;
        handler.postDelayed(pollTask, r.pollIntervalMs);
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
        int skippedWins = 0;

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

                    // ------------------------------------------------------------
                    // 安全过滤（多窗口扫描引入的新风险）：
                    // 现在会遍历所有窗口，如果不加限制，用户按 Home 回到桌面后，
                    // 桌面窗口也会被扫描，万一桌面上有像「跳过」的文字就会被误点。
                    // 所以：窗口所属包名若在 excludeApps 里（桌面/设置/安装器/系统UI），
                    // 除非它正是当前监听的应用，否则整窗跳过。
                    //
                    // 注意广告 SDK 的独立窗口包名**不在** excludeApps 里，所以照常扫描。
                    // ------------------------------------------------------------
                    String wpkg = null;
                    try {
                        CharSequence pn = rt.getPackageName();
                        if (pn != null) wpkg = pn.toString();
                    } catch (Throwable ignored) {
                    }
                    if (r.isExcludedWindow(wpkg, watchPkg)) {
                        skippedWins++;
                        continue;
                    }

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

        // ------------------------------------------------------------------
        // v3.0 稳健模式的安全闸门。
        //
        // 抖音的「跳过」在右上角，而主页的**搜索图标也在右上角**，
        // 两者位置高度重合；仅靠文字/ID 匹配无法可靠区分。
        // 所以在真正点击之前，再过一道「形状 + 位置」的硬约束：
        //   · 必须是**开着稳健模式**时的候选（safeMode 关掉则不拦）；
        //   · 只能点在屏幕顶部带或底部带内，绝不点屏幕正中（正文/输入区）；
        //   · 候选面积不能太大。
        // 宁可漏跳（老年人更容易接受「没跳」而不是「点错」），也不误触。
        // ------------------------------------------------------------------
        if (best != null && !best.safe) {
            lastScanReason = "已拦下不安全候选：" + best.reason
                    + "（面积" + Math.round(best.areaRatio * 100) + "%，纵位置"
                    + Math.round(best.ny * 100) + "%）";
            Log.i(TAG, lastScanReason);
            // 不点，但也不再重试同一类候选：直接结束本轮，避免反复评估。
            if (!r.continueAfterSkip) endWatch(false);
            return;
        }

        if (best == null) {
            lastScanReason = "未命中（窗口: " + winInfo
                    + (skippedWins > 0 ? "，已跳过 " + skippedWins + " 个排除窗口" : "") + "）";

            // 抓诊断快照：趁广告还在屏幕上时抓，而不是等 15 秒超时。
            // 开屏广告一般只显示 3~5 秒，等超时抓到的只会是 App 主界面。
            // 条件：开了诊断、本次还没抓过、已经过 400ms（等广告渲染出来）、
            //       且还在前 6 秒内（超过就认为是正常界面了）。
            if (!diagCapturedEarly && RuleStore.isDiagnose(this)
                    && (now - watchStart) >= 400L && (now - watchStart) <= 6000L) {
                diagCapturedEarly = true;
                captureDiagnostic(watchPkg, true);
            }

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
            ok = SkipEngine.clickNode(this, best.node, r.verifyBeforeTap);
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
     *
     * @param early true 表示这是「开屏早期」抓的（广告还在屏幕上，最有价值）；
     *              false 表示是超时兜底抓的（多半已是 App 主界面，仅供参考）
     */
    private void captureDiagnostic(String pkg, boolean early) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("包名: ").append(pkg).append('\n');
            sb.append("时间: ").append(
                    new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date())).append('\n');
            sb.append("时机: ").append(early ? "开屏早期（广告应该还在屏幕上）" : "超时兜底（可能已进入主界面）")
              .append("，距启动 ").append(android.os.SystemClock.uptimeMillis() - watchStart).append(" ms\n");

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
