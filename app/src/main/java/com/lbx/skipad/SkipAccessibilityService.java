package com.lbx.skipad;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
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
 * 开屏广告跳过服务（v3.4）。
 *
 * v1：只用 getRootInActiveWindow()，广告 SDK 的独立窗口扫不到。
 * v2：改用 getWindows() 遍历全部窗口，并给候选打分。
 * v3.0~v3.3：稳健模式、摇一摇放宽、身份确认、离开应用即收手。
 *
 * v3.4 修的三件事（对应两条真实反馈）：
 *
 * ① 「开机以后跳不过广告了」
 *    根因 A：整套逻辑**完全靠无障碍事件驱动**。开机后系统拉起服务的那一刻，
 *            事件流常常是「静默」的（服务绑定的时机早于界面渲染，或者 ROM 把
 *            首批事件吞了），于是一整局游戏里一个事件都没有 —— 界面照旧、扫描不跑。
 *            修法：加一条**主动前台轮询**（fgPollMs，默认 700ms），
 *            直接读活动窗口的包名来判断「是不是有应用起来了」。
 *            从此跳广告不再依赖任何事件，只剩「广告在不在屏幕上」这一件事。
 *    根因 B：服务刚连上时没有重新登记能力。部分 ROM 在开机重启后会带着上次的
 *            AccessibilityServiceInfo 空壳起来（事件类型/flags 丢失）。
 *            修法：连接时 setServiceInfo(getServiceInfo()) 重新登记，并在 1.5 秒后再来一次。
 *
 * ② 「卡在超级课程表的启动界面，进不去也退不出」
 *    根因 A：开屏窗口只有 6 秒，而「App 自己的启动页 → 广告 SDK 拉素材 → 广告盖上来」
 *            常常要 8~12 秒。观察窗口一收，之后广告显示多久都没人管。
 *            修法：splashWindowMs 默认 6s → 12s。
 *    根因 B：v3.3 的「首屏切走即收手」把上面这个过程读反了 ——
 *            启动页和广告页文字差异极大，被判成「用户已经走了」，立刻收工。
 *            修法：默认关闭该判断（firstScreenExit=false）。
 *    根因 C：退出应用（FG_LEAVE）后没有 return，会顺手在**桌面**上重开一轮观察，
 *            既可能误点桌面图标，又让「离开即停」形同虚设。
 *            修法：FG_LEAVE 直接 return。
 *    根因 D：跳过按钮是纯图片/视频覆盖层时，无障碍树里啥都没有，只能干等。
 *            修法：确认是广告页（有「互动广告/摇一摇」等措辞）且卡满 backKeyDelayMs
 *            仍没跳掉，执行一次系统返回键 —— 广告页的返回等价于「跳过」，
 *            这也是「退不出」的唯一出路。
 *
 * ③ 顺带修的性能问题：扫描原本全在**主线程**（100ms 一次 + 250ms 轮询 + 点击前
 *    双次全树复核），低端机上把主线程占满，表现为「界面卡住、点了没反应」。
 *    v3.4 把所有扫描与点击搬到独立 worker 线程，主线程只负责收事件、投递任务。
 */
public class SkipAccessibilityService extends AccessibilityService {

    private static final String TAG = "SkipAd";
    private static final int LOG_MAX = 60;

    /** 供界面查询服务是否已连接。 */
    private static volatile SkipAccessibilityService instance;

    /** 主线程：只做 Toast / 震动这类必须回到 UI 线程的事。 */
    private final Handler main = new Handler(Looper.getMainLooper());

    /** worker 线程：所有读窗口、扫描、点击、状态判断都在这里。 */
    private HandlerThread workerThread;
    private volatile Handler worker;

    /** 供界面查询的状态快照（worker 写，主线程读，仅用于显示）。 */
    private volatile String snapWatchPkg = null;
    private volatile String snapReason = null;

    private RuleStore.Rules rules;

    // ------------------------------------------------------------------
    // 观察窗口状态。全部只在 worker 线程读写，因此不需要加锁 —— 这是把状态机
    // 收敛到单线程带来的好处之一（v3.3 里事件与定时器会并发改这些字段）。
    // ------------------------------------------------------------------

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
     * 而观察窗口更长 —— 等到超时才抓，广告早就消失了，
     * 抓到的只是 App 主界面，对排查毫无帮助。
     */
    private boolean diagCapturedEarly = false;
    /** 是否已排入一次主动轮询。 */
    private boolean pollScheduled = false;

    /** 上一次扫描的简述，用于诊断。 */
    private String lastScanReason = null;

    // ---------------- v3.3：首屏指纹（v3.4 起默认不生效，见 firstScreenExit） ----------------

    private String firstScreenFingerprint = null;
    private boolean leftFirstScreen = false;

    // ---------------- v3.0 防误触状态 ----------------

    /** 上一次认定的前台应用包名，用于区分「真切换应用」和「应用内换界面」。 */
    private String lastFgPkg = null;
    /** 上一次观察的是哪个应用。 */
    private String lastEpisodePkg = null;
    /** 上一次观察结束的时刻（uptimeMillis）。 */
    private long lastEpisodeEndMs = 0L;
    /** 上一次观察结束时，是否真的跳成功过（v3.4：只有成功过才认冷却）。 */
    private boolean lastEpisodeHit = false;
    /** 自上次观察结束后，是否出现过系统窗口（桌面/设置等）——说明用户真的离开过。 */
    private boolean sawSystemSinceEpisode = false;

    /** 屏幕尺寸缓存。 */
    private int screenW = 0;
    private int screenH = 0;

    // ---------------- v3.4 自救状态 ----------------

    /** 本轮观察里「已确认是广告页」的第一个时刻；0 表示还没确认过。 */
    private long adPageSinceMs = 0L;
    /** 本轮观察里是否已经按过返回键（每轮最多一次，避免连按把应用退出去）。 */
    private boolean backKeyTried = false;
    /** 本轮观察里因为「不安全候选」放弃过几次。 */
    private int unsafeRejects = 0;
    /** 前台轮询是否已排入。 */
    private boolean fgScheduled = false;
    /** 当前是否已做过一次连接自检。 */
    private boolean selfCheckDone = false;

    // ------------------------------------------------------------------ 任务

    private final Runnable scanTask = new Runnable() {
        @Override
        public void run() {
            scanScheduled = false;
            try {
                doScan();
            } catch (Throwable t) {
                Log.e(TAG, "扫描异常", t);
            }
        }
    };

    /**
     * 观察窗口内的主动轮询。
     *
     * 为什么需要它（「有时能跳、有时不行」的根因之二）：
     * 广告若是纯图片 / 视频 / SurfaceView，渲染时可能**一个事件都不发**，
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
            long now = SystemClock.uptimeMillis();
            if (now > watchDeadline) {
                endWatch(true);
                return;
            }
            try {
                doScan();
            } catch (Throwable t) {
                Log.e(TAG, "轮询扫描异常", t);
            }
            if (watchPkg != null) schedulePoll();
        }
    };

    /**
     * 前台轮询（v3.4 的核心新增）。
     *
     * 它解决的是「开机后服务在跑，但一个事件都收不到」：
     * 直接读当前活动窗口的包名，自己去判断「有没有应用起来」，
     * 完全不依赖 onAccessibilityEvent。代价是每 fgPollMs 读一次窗口根节点
     * （只取包名，不做 DFS），远小于一次完整扫描。
     */
    private final Runnable fgTask = new Runnable() {
        @Override
        public void run() {
            fgScheduled = false;
            try {
                pollForeground();
            } catch (Throwable t) {
                Log.e(TAG, "前台轮询异常", t);
            }
            scheduleFgPoll();
        }
    };

    public static boolean isRunning() {
        return instance != null;
    }

    public static String statusText() {
        SkipAccessibilityService s = instance;
        if (s == null) return "未连接（请确认无障碍开关是否被系统关掉）";
        String w = s.snapWatchPkg;
        if (w == null) return "待命中（已就绪，打开别的应用即可）";
        String t = "监听中：" + w;
        String reason = s.snapReason;
        if (reason != null) t += "\n最近扫描：" + reason;
        return t;
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;

        startWorker();

        final Handler w = worker;
        if (w == null) return;
        w.post(new Runnable() {
            @Override
            public void run() {
                updateScreenSize();
                reloadRules();
                refreshServiceInfo();
                // 部分 ROM 会在连接后的头 1~2 秒把 info 覆盖回空壳，再补一次。
                w.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refreshServiceInfo();
                    }
                }, 1500L);
                selfCheck();
                // 立即来一次前台探测，不必等到第一次事件。
                scheduleFgPoll(0);
            }
        });
        Log.i(TAG, "服务已连接");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        shutdownWorker();
        Log.i(TAG, "服务已断开");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        shutdownWorker();
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }

    // ------------------------------------------------------------------ 线程

    private void startWorker() {
        if (workerThread != null) return;
        workerThread = new HandlerThread("skipad-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    private void shutdownWorker() {
        Handler w = worker;
        if (w != null) {
            w.removeCallbacksAndMessages(null);
        }
        worker = null;
        HandlerThread t = workerThread;
        workerThread = null;
        if (t != null) {
            try {
                t.quitSafely();
            } catch (Throwable ignored) {
            }
        }
        scanScheduled = false;
        pollScheduled = false;
        fgScheduled = false;
        watchPkg = null;
        snapWatchPkg = null;
    }

    /**
     * 重新登记无障碍能力。
     *
     * 开机后系统拉起服务的时机很早，部分 ROM 会给一个「事件类型/flags 都为空」的
     * AccessibilityServiceInfo —— 服务在跑，却收不到任何事件，这正是
     * 「开机以后就不灵了，重启一下服务又好了」的成因。把当前 info 原样写回，
     * 等于让系统按 xml 配置重新登记一次监听。
     */
    private void refreshServiceInfo() {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) setServiceInfo(info);
            Log.i(TAG, "无障碍能力已重新登记");
        } catch (Throwable t) {
            Log.e(TAG, "重新登记无障碍能力失败", t);
        }
    }

    /**
     * 连接自检：把「服务确实起来了」这件事写进日志，并把主开关状态也记下来。
     *
     * 为什么值得写一条日志：用户说「开机以后跳广告不起作用」时，第一件要分辨的
     * 就是「服务压根没起来」还是「起来了但没跳成」。有了这条记录，打开
     * 「查看日志」一眼就能分清 —— 没有这条就是系统没拉起服务（去查电池优化/自启），
     * 有这条却仍不跳，才是规则或界面问题。
     */
    private void selfCheck() {
        if (selfCheckDone) return;
        selfCheckDone = true;

        boolean master = RuleStore.isMasterEnabled(this);
        boolean diagnose = RuleStore.isDiagnose(this);
        String ver = "v3.4";
        try {
            ver = "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
        }
        appendLog("服务自检", ver + " 已连接 · 总开关" + (master ? "开" : "关")
                + " · 诊断" + (diagnose ? "开" : "关")
                + " · 扫描线程就绪 · 前台轮询已启动");
        Log.i(TAG, "自检完成");
    }

    // ------------------------------------------------------------------ 事件入口

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (!RuleStore.isMasterEnabled(this)) {
            // 主开关关掉时，把还在跑的定时器也停掉，避免空转。
            Handler w = worker;
            if (w != null && watchPkg != null) {
                w.post(new Runnable() {
                    @Override
                    public void run() {
                        endWatch(false);
                    }
                });
            }
            return;
        }

        final Handler w = worker;
        if (w == null) return;

        final int type = event.getEventType();
        CharSequence pn = null;
        try {
            pn = event.getPackageName();
        } catch (Throwable ignored) {
        }
        final String pkg = pn == null ? null : pn.toString();

        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
                && type != AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            return;
        }

        final boolean stateChanged = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;

        w.post(new Runnable() {
            @Override
            public void run() {
                long now = SystemClock.uptimeMillis();
                if (stateChanged && pkg != null) {
                    handleForeground(pkg, now);
                }
                onEventTick(now);
            }
        });
    }

    /**
     * 事件带来的「可能该扫一下」。
     *
     * 注意：这里**不再**自己判断超时/重试上限后就 return —— 那些判断已经由
     * doScan() 统一处理。事件路径只负责「催一次扫描」，逻辑收敛到一处，
     * 才不会出现「某条路径忘了判断某个上限」这类分叉 bug。
     */
    private void onEventTick(long now) {
        if (!RuleStore.isMasterEnabled(this)) return;
        RuleStore.Rules r = rules;
        if (r == null) {
            reloadRules();
            r = rules;
            if (r == null) return;
        }
        if (watchPkg == null) return;
        scheduleScan(now);
    }

    // ------------------------------------------------------------------ 前台判定

    /** 主动读一次当前前台，喂给与事件路径相同的决策逻辑。 */
    private void pollForeground() {
        if (!RuleStore.isMasterEnabled(this)) return;

        String pkg = readActivePkg();
        if (pkg == null) return;

        RuleStore.Rules r = rules;
        if (r == null) {
            reloadRules();
            r = rules;
            if (r == null) return;
        }

        // 广告 SDK 的覆盖窗口可能成为「活动窗口」，此时活动包名不是我们关心的应用。
        // 不能让轮询就此放弃 —— 那正是「事件没收到、广告又盖在上面」的死角。
        // 这时去全部窗口里找那个真正需要监听的应用包名。
        if (!r.shouldWatch(pkg) && watchPkg == null) {
            String owner = findWatchableWindowPkg(r);
            if (owner != null) pkg = owner;
        }

        handleForeground(pkg, SystemClock.uptimeMillis());
    }

    private String readActivePkg() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return null;
            CharSequence pn = root.getPackageName();
            return pn == null ? null : pn.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 在全部窗口里找出第一个「需要监听」的包名（用于活动窗口是广告覆盖层的情况）。 */
    private String findWatchableWindowPkg(RuleStore.Rules r) {
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins == null) return null;
            for (AccessibilityWindowInfo w : wins) {
                if (w == null) continue;
                AccessibilityNodeInfo rt = null;
                try {
                    rt = w.getRoot();
                } catch (Throwable ignored) {
                }
                if (rt == null) continue;
                String wp = null;
                try {
                    CharSequence pn = rt.getPackageName();
                    if (pn != null) wp = pn.toString();
                } catch (Throwable ignored) {
                }
                if (wp != null && r.shouldWatch(wp)) return wp;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void handleForeground(String pkg, long now) {
        if (pkg == null || pkg.isEmpty()) return;

        // ---- 快路径：观察中且包名没变，什么都不用做（不必重读规则） ----
        if (pkg.equals(watchPkg)) {
            lastFgPkg = pkg;
            return;
        }

        RuleStore.Rules r = rules;
        if (r == null) {
            reloadRules();
            r = rules;
            if (r == null) return;
        }

        // ---- 快路径：没在观察，且前台应用没变（同一应用内换界面）----
        // 这条是 v3.0 防误触的关键：没有它，App 内部任何窗口变化都会重开一轮观察，
        // 于是把扫描带进正常界面（抖音的全屏搜索就是这么被误点的）。
        if (pkg.equals(lastFgPkg) && watchPkg == null) {
            return;
        }

        // 走到这里说明可能是一次真正的切换，先让用户刚改过的规则立刻生效。
        reloadRules();
        r = rules;
        if (r == null) return;

        // 记录「见过系统窗口」：用户回过桌面 = 确实离开过该应用，
        // 下次再进同一个应用就不该被冷却挡住。
        if (!pkg.equals(watchPkg) && !r.shouldWatch(pkg)) {
            sawSystemSinceEpisode = true;
        }

        // ------------------------------------------------------------
        // 注意顺序：必须先把**旧**的 lastFgPkg 交给决策，再更新字段。
        // decideForeground 用 ctx.lastFgPkg 判断「是不是同一应用内部换界面」，
        // 如果先更新再传，那个判断永远是「相同」，于是：
        //   · 没在观察时永远不会开始观察（怎么开应用都不跳）；
        //   · 观察中遇到别的包名会被当成 FG_SAME 直接 return，
        //     「离开即停」同样失效。
        // 这个坑在 v3.3 里没有（原代码是先存 ctx 再更新），v3.4 重写时挪反了一步。
        // ------------------------------------------------------------
        RuleStore.Rules.FgContext ctx = new RuleStore.Rules.FgContext();
        ctx.watchingPkg = watchPkg;
        ctx.lastFgPkg = lastFgPkg;
        ctx.lastEpisodePkg = lastEpisodePkg;
        ctx.lastEpisodeEndMs = lastEpisodeEndMs;
        ctx.lastEpisodeHit = lastEpisodeHit;
        ctx.sawSystemSinceEpisode = sawSystemSinceEpisode;
        ctx.now = now;

        int action = r.decideForeground(pkg, ctx);

        // 决策已用掉旧值，现在才把「当前前台」记下来，供下一次比较。
        lastFgPkg = pkg;

        if (action == RuleStore.Rules.FG_SAME || action == RuleStore.Rules.FG_IGNORE) {
            return;
        }

        if (action == RuleStore.Rules.FG_KEEP) {
            // 观察中遇到了别的包名（多半是广告 SDK 的独立窗口）：
            // 不接管、不重启锚点，但界面确实变了，催一次扫描。
            scheduleScan(now);
            return;
        }

        if ((action == RuleStore.Rules.FG_SWITCH || action == RuleStore.Rules.FG_LEAVE)
                && watchPkg != null) {
            endWatch(false);
        }

        // ------------------------------------------------------------
        // v3.4 关键修复：用户已离开应用（回到桌面/设置等）时**到此为止**。
        //
        // v3.3 这里只是 endWatch(false) 然后继续往下走，于是：
        //   · 在**桌面**上重新开了一轮 6 秒观察；
        //   · 桌面包名在 excludeApps 里，窗口被全部跳过，roots 为空，
        //     兜底用 getRootInActiveWindow() 扫桌面 —— 桌面上若有像「跳过」的文字
        //     就会被点；
        //   · 「离开即停」这条 v3.3 的主打功能实际上从未生效过。
        // ------------------------------------------------------------
        if (action == RuleStore.Rules.FG_LEAVE) {
            snapWatchPkg = null;
            Log.i(TAG, "用户已离开，结束观察且不再重开");
            return;
        }

        // FG_START：新一轮观察。
        startWatch(pkg, now, r);
    }

    private void startWatch(String pkg, long now, RuleStore.Rules r) {
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
        firstScreenFingerprint = null;
        leftFirstScreen = false;
        adPageSinceMs = 0L;
        backKeyTried = false;
        unsafeRejects = 0;
        // 新一轮观察开始，重置「期间是否回过系统界面」的标记。
        // 这个标记是给**本轮结束后**的冷却判断用的：必须在这里清掉，
        // 否则一旦用户回过桌面它就永远是 true，冷却逻辑将永久失效。
        sawSystemSinceEpisode = false;
        lastScan = 0L;
        lastScanReason = null;
        updateScreenSize();

        snapWatchPkg = pkg;
        snapReason = null;

        scheduleScan(now);
        schedulePoll();
        Log.i(TAG, "开始监听 " + pkg + "，窗口 " + limit + "ms");
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
        // 记下这次观察是针对哪个应用、何时结束、有没有真的跳成功过，
        // 供冷却判断使用（v3.4：只有成功过才冷却 —— 没跳成必须允许立刻重试）。
        if (watchPkg != null) {
            lastEpisodePkg = watchPkg;
            lastEpisodeEndMs = SystemClock.uptimeMillis();
            lastEpisodeHit = skippedOnce;
        }
        watchPkg = null;
        snapWatchPkg = null;
        Handler w = worker;
        if (w != null) {
            w.removeCallbacks(scanTask);
            w.removeCallbacks(pollTask);
        }
        scanScheduled = false;
        pollScheduled = false;
        adPageSinceMs = 0L;
    }

    private void scheduleScan(long now) {
        RuleStore.Rules r = rules;
        if (r == null || watchPkg == null) return;
        Handler w = worker;
        if (w == null) return;
        if (scanScheduled) return;
        long since = now - lastScan;
        long delay = since >= r.scanIntervalMs ? 0 : (r.scanIntervalMs - since);
        scanScheduled = true;
        w.postDelayed(scanTask, delay);
    }

    /** 排入下一次主动轮询（与事件驱动的 scheduleScan 互补）。 */
    private void schedulePoll() {
        RuleStore.Rules r = rules;
        if (r == null || watchPkg == null) return;
        Handler w = worker;
        if (w == null) return;
        if (pollScheduled) return;
        pollScheduled = true;
        w.postDelayed(pollTask, r.pollIntervalMs);
    }

    /** 排入下一次前台轮询（v3.4：与事件完全无关的看门狗）。 */
    private void scheduleFgPoll() {
        scheduleFgPoll(-1L);
    }

    private void scheduleFgPoll(long delayOverride) {
        Handler w = worker;
        if (w == null) return;
        if (fgScheduled) return;
        fgScheduled = true;
        long delay;
        if (delayOverride >= 0) {
            delay = delayOverride;
        } else {
            RuleStore.Rules r = rules;
            long interval = 700L;
            if (r != null && r.fgPollMs > 0) interval = r.fgPollMs;
            // 观察期内要更勤快一点：开屏广告只存在几秒，早一眼看到就早一点跳掉。
            if (watchPkg != null) interval = Math.max(220L, interval / 2);
            delay = interval;
        }
        w.postDelayed(fgTask, delay);
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

    private void doScan() {
        final RuleStore.Rules r = rules;
        if (r == null) return;
        if (watchPkg == null) return;

        long now = SystemClock.uptimeMillis();
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

        // ---- 首屏已切走即收手（v3.4 默认关闭） ----
        // 抓「首屏文字指纹」：在观察刚开始、广告最可能在屏幕上时记一次。
        if (r.firstScreenExit) {
            if (firstScreenFingerprint == null && (now - watchStart) <= 3000L) {
                firstScreenFingerprint = fingerprintWindows(roots);
            }
            // 整段观察还没命中任何候选、且当前界面和首屏文字已经差很远，
            // 说明用户已经正常进入/离开应用（不是卡在开屏广告上了），
            // 没必要继续扫、继续点，及时收工，避免「应用内导航被当成广告反复点」。
            if (best == null && firstScreenFingerprint != null && !leftFirstScreen
                    && (now - watchStart) > 1500L) {
                String cur = fingerprintWindows(roots);
                if (!firstScreenFingerprint.equals(cur) && diffRatio(firstScreenFingerprint, cur) >= 0.5) {
                    leftFirstScreen = true;
                    lastScanReason = "首屏已切走，停止本轮观察（界面与开屏差异过大）";
                    Log.i(TAG, lastScanReason);
                    endWatch(false);
                    return;
                }
            }
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
            unsafeRejects++;
            lastScanReason = "已拦下不安全候选：" + best.reason
                    + "（面积" + Math.round(best.areaRatio * 100) + "%，纵位置"
                    + Math.round(best.ny * 100) + "%"
                    + (best.adSignal ? "，摇一摇广告页但仍在走廊内" : "") + "）";
            Log.i(TAG, lastScanReason);
            snapReason = lastScanReason;
            // v3.4：不因第一次遇到不安全候选就整轮放弃。
            // 广告刚渲染出来时布局还没稳定，外层多半是整屏容器；
            // 等半秒再评估，往往就能看到真正的那个小按钮。
            if (unsafeRejects >= r.unsafeRejectLimit && !r.continueAfterSkip) {
                maybeBackKey(now, roots, r, false);
                endWatch(false);
            }
            return;
        }

        if (best == null) {
            lastScanReason = "未命中（窗口: " + winInfo
                    + (skippedWins > 0 ? "，已跳过 " + skippedWins + " 个排除窗口" : "") + "）";

            boolean adPage = detectAdPage(roots, r);
            if (adPage) markAdPage(now);
            if (adPage && !lastScanReason.contains("摇一摇")) {
                lastScanReason += "，检测到广告页特征";
            }
            snapReason = lastScanReason;

            // ------------------------------------------------------------
            // v3.1 摇一摇广告兜底。
            //
            // 有些摇一摇广告的「跳过」是纯图片、连节点 ID 都没有，
            // 无障碍树里根本找不到任何候选。这时唯一还能做的，是在**已确认
            // 是广告页**（页面上有「摇一摇」字样）的前提下，按坐标点一次。
            // 默认关闭（shakeBlindTap=false）：这是全应用里唯一凭坐标猜的动作，
            // 风险最高，必须用户显式打开。
            // ------------------------------------------------------------
            if (adPage && r.shakeBlindTap && !blindTried && attempts < r.retryLimit) {
                blindTried = true;
                attempts++;
                boolean ok = SkipEngine.tapNormalized(this, screenW, screenH,
                        r.shakeTapX, r.shakeTapY);
                if (ok) {
                    lastScanReason = "摇一摇广告兜底点击 " + r.shakeTapX + "," + r.shakeTapY;
                    snapReason = lastScanReason;
                }
            }

            // 抓诊断快照：趁广告还在屏幕上时抓，而不是等超时。
            // 开屏广告一般只显示 3~5 秒，等超时抓到的只会是 App 主界面。
            if (!diagCapturedEarly && RuleStore.isDiagnose(this)
                    && (now - watchStart) >= 400L && (now - watchStart) <= 6000L) {
                diagCapturedEarly = true;
                captureDiagnostic(watchPkg, true);
            }

            // 盲点兜底：实在找不到候选时，按用户设定盲点一次
            if (!blindTried && r.blindTapEnabled && attempts < r.retryLimit) {
                blindTried = true;
                attempts++;
                boolean ok = SkipEngine.tapNormalized(this, screenW, screenH,
                        r.blindTapX, r.blindTapY);
                if (ok) {
                    lastScanReason = "盲点兜底 " + r.blindTapX + "," + r.blindTapY;
                    snapReason = lastScanReason;
                }
            }

            // v3.4：确认是广告页、又怎么都跳不掉、已经卡够久 -> 按一次返回键。
            maybeBackKey(now, roots, r, adPage);
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
            snapReason = lastScanReason;
            // 点了却点不动（按钮不可点、被覆盖层挡住），同样要能自救。
            boolean adPage = detectAdPage(roots, r);
            if (adPage) markAdPage(now);
            maybeBackKey(now, roots, r, adPage);
        }
    }

    private boolean detectAdPage(List<AccessibilityNodeInfo> roots, RuleStore.Rules r) {
        if (!r.shakeAware || roots == null || roots.isEmpty()) return false;
        try {
            int[] probe = new int[]{SkipEngine.MAX_NODES / 2};
            for (int i = 0; i < roots.size(); i++) {
                if (SkipEngine.hasAdSignal(roots.get(i), r, probe)) return true;
                if (probe[0] <= 0) break;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void markAdPage(long now) {
        if (adPageSinceMs == 0L) adPageSinceMs = now;
    }

    /**
     * 「卡在开屏广告上，进不去也退不出」的最后一道自救。
     *
     * 触发条件全部满足才动手：
     *   1) 用户开了 backKeyFallback（默认开）；
     *   2) 本轮已确认这是**广告页**（页面上有互动广告/摇一摇这类措辞）——
     *      没有这条，在正常界面上按返回就是帮用户退出应用，比不跳广告严重得多；
     *   3) 从确认广告页算起已经卡够 backKeyDelayMs；
     *   4) 本轮还没按过（每轮最多一次，绝不连按）。
     *
     * 为什么返回键有用：开屏广告的返回键行为被广告 SDK 重写成「结束广告」，
     * 所以它对用户而言等价于「跳过」；即便不等价，也至少让用户能退出，
     * 不至于既进不去主界面、也退不出这个界面。
     */
    private void maybeBackKey(long now, List<AccessibilityNodeInfo> roots,
                              RuleStore.Rules r, boolean adPage) {
        if (!r.backKeyFallback || backKeyTried) return;
        if (!adPage || adPageSinceMs == 0L) return;
        if (now - adPageSinceMs < r.backKeyDelayMs) return;
        if (skippedOnce) return;

        backKeyTried = true;
        boolean ok = false;
        try {
            ok = performGlobalAction(GLOBAL_ACTION_BACK);
        } catch (Throwable t) {
            Log.e(TAG, "返回键兜底失败", t);
        }
        lastScanReason = "卡住自救：确认是广告页但 " + (now - adPageSinceMs) + "ms 内跳不掉，"
                + (ok ? "已按返回键结束广告" : "按返回键失败");
        snapReason = lastScanReason;
        Log.i(TAG, lastScanReason);
        appendLog(rules != null ? rules.nameOf(watchPkg) : String.valueOf(watchPkg), lastScanReason);
    }

    private void onSkipped(String pkg, String reason) {
        String app = rules != null ? rules.nameOf(pkg) : pkg;
        Log.i(TAG, "已跳过 " + app + " / " + reason);
        snapReason = "已跳过：" + reason;

        if (RuleStore.isToast(this)) {
            final String msg = "已跳过广告 · " + app;
            main.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Toast.makeText(SkipAccessibilityService.this, msg, Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {
                    }
                }
            });
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

    // ------------------------------------------------------------------ 首屏指纹

    /**
     * 取一组窗口根节点的「文字指纹」：所有可见文字节点去空白后拼成的集合串。
     * 仅用于「首屏是否已切走」判断，不求精确，只要能反映界面大变即可。
     */
    private static String fingerprintWindows(List<AccessibilityNodeInfo> roots) {
        java.util.Set<String> set = new java.util.TreeSet<String>();
        if (roots != null) {
            for (AccessibilityNodeInfo rt : roots) {
                collectFingerprint(rt, set);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String s : set) sb.append(s).append('\n');
        return sb.toString();
    }

    private static void collectFingerprint(AccessibilityNodeInfo n, java.util.Set<String> set) {
        if (n == null) return;
        CharSequence t = null;
        try { t = n.getText(); } catch (Throwable ignored) {}
        if (t != null && t.length() > 0) {
            StringBuilder c = new StringBuilder();
            for (int i = 0; i < t.length(); i++) {
                char ch = t.charAt(i);
                if (!Character.isWhitespace(ch)) c.append(ch);
            }
            if (c.length() > 0) set.add(c.toString());
        }
        try {
            int cnt = n.getChildCount();
            for (int i = 0; i < cnt; i++) {
                collectFingerprint(n.getChild(i), set);
            }
        } catch (Throwable ignored) {}
    }

    /** 两个指纹串的「新增/消失」比例（取较大差异除以较小行数），用于判断是否大变。 */
    private static double diffRatio(String a, String b) {
        String[] la = a.split("\n", -1);
        String[] lb = b.split("\n", -1);
        java.util.Set<String> sa = new java.util.HashSet<String>(java.util.Arrays.asList(la));
        java.util.Set<String> sb = new java.util.HashSet<String>(java.util.Arrays.asList(lb));
        int onlyA = 0, onlyB = 0;
        for (String s : sa) if (!sb.contains(s)) onlyA++;
        for (String s : sb) if (!sa.contains(s)) onlyB++;
        int total = onlyA + onlyB;
        int base = Math.min(sa.size(), sb.size());
        if (base == 0) return total > 0 ? 1.0 : 0.0;
        return (double) total / base;
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
              .append("，距启动 ").append(SystemClock.uptimeMillis() - watchStart).append(" ms\n");

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
