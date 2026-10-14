package com.lbx.skipad;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则的读写与解析。
 *
 * 规则以 JSON 文本存放在 SharedPreferences 中，用户可以在界面上直接编辑。
 * 默认规则内置在 res/raw/default_rules.json。
 */
final class RuleStore {

    static final String PREF = "skipad_prefs";
    static final String K_RULES = "rules_json";
    static final String K_MASTER = "master_enabled";
    static final String K_VIBRATE = "vibrate";
    static final String K_TOAST = "toast";
    static final String K_LOG = "log_json";
    /** 诊断快照（跳不掉时抓的窗口树摘要）。 */
    static final String K_DIAG = "diag_json";
    /** 是否在跳不掉时自动抓诊断快照。 */
    static final String K_DIAGNOSE = "diagnose";

    private RuleStore() {}

    // ------------------------------------------------------------------ 配置项

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    static boolean isMasterEnabled(Context c) {
        return prefs(c).getBoolean(K_MASTER, true);
    }

    static void setMasterEnabled(Context c, boolean v) {
        prefs(c).edit().putBoolean(K_MASTER, v).apply();
    }

    static boolean isVibrate(Context c) {
        return prefs(c).getBoolean(K_VIBRATE, true);
    }

    static void setVibrate(Context c, boolean v) {
        prefs(c).edit().putBoolean(K_VIBRATE, v).apply();
    }

    static boolean isToast(Context c) {
        return prefs(c).getBoolean(K_TOAST, true);
    }

    static void setToast(Context c, boolean v) {
        prefs(c).edit().putBoolean(K_TOAST, v).apply();
    }

    /** 跳不掉时是否自动抓一份窗口树快照（默认开，便于排查）。 */
    static boolean isDiagnose(Context c) {
        return prefs(c).getBoolean(K_DIAGNOSE, true);
    }

    static void setDiagnose(Context c, boolean v) {
        prefs(c).edit().putBoolean(K_DIAGNOSE, v).apply();
    }

    // ------------------------------------------------------------------ 默认规则

    static String readDefaultJson(Context c) {
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getResources().openRawResource(R.raw.default_rules), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            return sb.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    static String getRawJson(Context c) {
        String s = prefs(c).getString(K_RULES, null);
        if (s == null || s.trim().isEmpty()) s = readDefaultJson(c);
        return s;
    }

    static void setRawJson(Context c, String json) {
        prefs(c).edit().putString(K_RULES, json).apply();
    }

    static void resetToDefault(Context c) {
        prefs(c).edit().putString(K_RULES, readDefaultJson(c)).apply();
    }

    /**
     * 校验 JSON 是否可用。返回 null 表示没问题，否则返回给用户看的错误信息。
     */
    static String validate(Context c, String json) {
        try {
            Rules r = parse(json);
            if (r.skipTexts.isEmpty() && r.skipIds.isEmpty()) {
                return "至少需要一条“跳过文字”或“控件 ID”规则，否则无法识别跳过按钮。";
            }
            return null;
        } catch (Throwable t) {
            return "JSON 格式错误：" + t.getMessage();
        }
    }

    static Rules load(Context c) {
        try {
            return parse(getRawJson(c));
        } catch (Throwable t) {
            try {
                return parse(readDefaultJson(c));
            } catch (Throwable ignored) {
                return new Rules();
            }
        }
    }

    static Rules parse(String json) throws Exception {
        Rules r = new Rules();
        JSONObject root = new JSONObject(json);

        JSONObject g = root.optJSONObject("global");
        if (g != null) {
            r.watchAll = g.optBoolean("watchAll", true);
            r.scanIntervalMs = clamp(g.optInt("scanIntervalMs", 120), 40, 2000);
            r.maxTextLen = clamp(g.optInt("maxTextLen", 8), 2, 20);
            r.maxWaitMs = clamp(g.optInt("maxWaitMs", 15000), 2000, 120000);
            r.retryLimit = clamp(g.optInt("retryLimit", 5), 1, 50);
            r.continueAfterSkip = g.optBoolean("continueAfterSkip", false);
            r.blindTapEnabled = g.optBoolean("blindTapEnabled", false);
            r.blindTapX = clampDouble(g.optDouble("blindTapX", 0.86), 0.0, 1.0);
            r.blindTapY = clampDouble(g.optDouble("blindTapY", 0.07), 0.0, 1.0);
            // 轮询间隔：即使一个无障碍事件都没有，也要按这个周期主动扫描。
            // 这是「有时能跳、有时不能」的根治手段（原实现纯事件驱动）。
            r.pollIntervalMs = clamp(g.optInt("pollIntervalMs", 250), 80, 2000);

            // ----------------------------------------------------------
            // v3.0 防误触（默认全开，面向老年用户：宁可漏跳，绝不误触）
            // ----------------------------------------------------------
            r.safeMode = g.optBoolean("safeMode", true);
            // 只在回到前台后的这段时间内才认为是「开屏期」并允许扫描。
            // 超过就停止，避免在正常界面（如抖音全屏搜索）里乱找按钮。
            r.splashWindowMs = clamp(g.optInt("splashWindowMs", 6000), 1000, 60000);
            // 同一应用两次观察之间的冷却时间。防止「观察结束 -> 界面变化 -> 重新开观察」
            // 这个循环把扫描带进正常界面。
            r.episodeCooldownMs = clamp(g.optInt("episodeCooldownMs", 60000), 1000, 600000);
            // 点击前复核目标是否还在原位（防 dispatchGesture 异步导致点击穿透）。
            r.verifyBeforeTap = g.optBoolean("verifyBeforeTap", true);
            // 稳健模式下候选按钮的面积上限（占窗口比例）。跳过按钮都很小。
            r.safeMaxArea = clampDouble(g.optDouble("safeMaxArea", 0.12), 0.01, 1.0);
            // 稳健模式下的「安全带」：只在屏幕顶部/底部这条带内点击，
            // 屏幕中部通常是正文/输入区，点错代价大。
            r.safeTopBand = clampDouble(g.optDouble("safeTopBand", 0.30), 0.05, 0.6);
            r.safeBottomBand = clampDouble(g.optDouble("safeBottomBand", 0.75), 0.4, 0.99);
        }

        r.skipTexts = toList(root.optJSONArray("skipTexts"));
        r.strongTexts = toList(root.optJSONArray("strongTexts"));
        r.denyTexts = toList(root.optJSONArray("denyTexts"));
        r.skipIds = toList(root.optJSONArray("skipIds"));
        r.excludeApps = new HashSet<String>(toList(root.optJSONArray("excludeApps")));

        JSONObject apps = root.optJSONObject("apps");
        if (apps != null) {
            java.util.Iterator<String> it = apps.keys();
            while (it.hasNext()) {
                String pkg = it.next();
                JSONObject a = apps.optJSONObject(pkg);
                if (a == null) continue;
                AppRule ar = new AppRule();
                ar.pkg = pkg;
                ar.name = a.optString("name", pkg);
                ar.enabled = a.optBoolean("enabled", true);
                ar.extraTexts = toList(a.optJSONArray("extraTexts"));
                ar.extraIds = toList(a.optJSONArray("extraIds"));
                r.apps.put(pkg, ar);
            }
        }
        return r;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static double clampDouble(double v, double lo, double hi) {
        if (Double.isNaN(v)) return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static List<String> toList(JSONArray arr) {
        List<String> out = new ArrayList<String>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, null);
            if (s != null && !s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    // ------------------------------------------------------------------ 数据模型

    static final class AppRule {
        String pkg = "";
        String name = "";
        boolean enabled = true;
        List<String> extraTexts = new ArrayList<String>();
        List<String> extraIds = new ArrayList<String>();
    }

    static final class Rules {
        boolean watchAll = true;
        int scanIntervalMs = 120;
        int maxTextLen = 8;
        int maxWaitMs = 15000;
        int retryLimit = 5;
        boolean continueAfterSkip = false;

        /** 盲点兜底：完全找不到候选时，按归一化坐标点一次（默认关，有误点风险）。 */
        boolean blindTapEnabled = false;
        double blindTapX = 0.86;
        double blindTapY = 0.07;
        /** 主动轮询间隔（毫秒）。即使没有任何无障碍事件，也按此周期扫描。 */
        int pollIntervalMs = 250;

        // ---------------- v3.0 防误触 ----------------

        /**
         * 稳健模式（默认开）。面向「宁可不跳，也不要点错」的场景（如老年用户）。
         * 开启后会额外限制：候选必须小、必须落在安全带内、必须来自开屏期。
         */
        boolean safeMode = true;
        /** 开屏期时长（毫秒）：只在这段时间内才允许扫描点击。 */
        int splashWindowMs = 6000;
        /** 同一应用两次观察之间的冷却（毫秒），防止把扫描带进正常界面。 */
        int episodeCooldownMs = 60000;
        /** 点击前复核目标仍在其原位置（防异步点击穿透）。 */
        boolean verifyBeforeTap = true;
        /** 稳健模式下允许的最大候选面积占比。 */
        double safeMaxArea = 0.12;
        /** 稳健模式的安全带：只有落在这两条归一化纵坐标之间才允许点击。 */
        double safeTopBand = 0.30;
        double safeBottomBand = 0.75;

        /**
         * 判断某个候选在稳健模式下是否安全可点。
         *
         * 为什么需要它：抖音的「跳过」在右上角，而主页的**搜索图标也在右上角**，
         * 两者位置高度重合。仅靠文字/ID 匹配无法可靠区分，
         * 所以再加一层「形状与位置」的约束 —— 跳过按钮一定是小控件，
         * 且出现在屏幕顶部或底部，绝不落在正中央（那里是正文/输入区）。
         */
        boolean isSafeCandidate(double areaRatio, double ny) {
            if (!safeMode) return true;
            // 太大的一律不要（跳过按钮都是小的）
            if (areaRatio > 0 && areaRatio > safeMaxArea) return false;
            // 必须落在顶部带或底部带内
            return ny <= safeTopBand || ny >= safeBottomBand;
        }

        List<String> skipTexts = new ArrayList<String>();
        List<String> strongTexts = new ArrayList<String>();
        List<String> denyTexts = new ArrayList<String>();
        List<String> skipIds = new ArrayList<String>();
        Set<String> excludeApps = new HashSet<String>();
        Map<String, AppRule> apps = new HashMap<String, AppRule>();

        /** 该应用是否需要进入“开屏监听”状态。 */
        boolean shouldWatch(String pkg) {
            if (pkg == null || pkg.isEmpty()) return false;
            if (pkg.equals("com.lbx.skipad")) return false;
            if (excludeApps.contains(pkg)) return false;
            AppRule ar = apps.get(pkg);
            if (ar != null && !ar.enabled) return false;
            return watchAll || ar != null;
        }

        /**
         * 扫描时是否应跳过这个窗口（按窗口所属包名判断）。
         *
         * 多窗口扫描带来一个新风险：遍历所有窗口后，用户按 Home 回桌面时
         * 桌面窗口也会被扫到，若桌面上恰有像「跳过」的文字就会被误点。
         * 所以排除名单里的包名（桌面/设置/安装器/系统 UI）整窗跳过 ——
         * 但如果它正是我们当前在监听的应用，则照常扫描。
         *
         * 广告 SDK 的独立窗口包名不在此名单内，所以不受影响。
         */
        boolean isExcludedWindow(String winPkg, String watchingPkg) {
            if (winPkg == null || winPkg.isEmpty()) return false;   // 拿不到包名时不冒险跳过
            if (winPkg.equals(watchingPkg)) return false;           // 正是当前监听的应用
            return excludeApps.contains(winPkg);
        }

        /**
         * 收到「前台应用变了」事件时的决策上下文。
         *
         * 把这些状态显式传进来（而不是藏在 Service 的字段里），
         * 是为了让整套防误触逻辑能在桌面 JVM 上直接单测 ——
         * 误触问题靠读代码很难验证，但可以断言。
         */
        static final class FgContext {
            /** 当前正在观察（监听开屏）的应用；null 表示没在观察。 */
            String watchingPkg;
            /** 上一次认定的「前台应用」包名（用于判断是否真的发生了应用切换）。 */
            String lastFgPkg;
            /** 上一次观察的是哪个应用。 */
            String lastEpisodePkg;
            /** 上一次观察结束的时刻（uptimeMillis）。 */
            long lastEpisodeEndMs;
            /** 自上次观察结束后，是否出现过桌面/系统窗口（说明用户真的离开过该应用）。 */
            boolean sawSystemSinceEpisode;
            /** 当前时刻（uptimeMillis）。 */
            long now;
        }

        /**
         * 收到「前台应用变了」事件时该怎么办。v3.0 重写。
         *
         * 这是 v2.x 里误触的两个根源所在：
         *
         *  误触 1「打开抖音误触右上角搜索」：
         *    抖音的「跳过」在右上角，主页搜索图标**也在右上角**，位置高度重合。
         *    而且开屏广告常由广告 SDK 渲染在**独立窗口**里 —— 那个包名一到，
         *    v2.1 会把观察「接管」到广告 SDK 包上；等广告消失、抖音主界面回来时，
         *    又被当成一次新的前台切换，于是**在正常界面上重新开了一个观察窗口**，
         *    就去点搜索图标了。
         *    修法：观察一旦开始就**锚定**在那个应用上，任何其它包名都不接管、不重启
         *    （见下面的 FG_KEEP 分支）。反正我们是遍历全部窗口扫描的，
         *    广告窗口照样能被扫到，根本不需要换锚点。
         *
         *  误触 2「打开全屏搜索也会误触」：
         *    v2.1 观察结束（endWatch 清空 watchingPkg）后，
         *    同一应用内的任何窗口变化都会被判成 FG_START 而**重新开始观察**。
         *    修法：区分「真正切换了应用」和「同一应用内部换界面」——
         *    后者（pkg == lastFgPkg）在没在观察时一律 FG_IGNORE。
         */
        int decideForeground(String pkg, FgContext ctx) {
            if (pkg == null || pkg.isEmpty()) return FG_IGNORE;

            // 同一应用内切界面：观察中就继续观察；没在观察就什么都别做。
            if (pkg.equals(ctx.watchingPkg)) return FG_SAME;

            // 前台应用没变（只是同应用内的窗口状态变化）：
            // 观察中无所谓，没在观察时**绝不能**借此重新开始观察。
            if (pkg.equals(ctx.lastFgPkg)) {
                return ctx.watchingPkg != null ? FG_SAME : FG_IGNORE;
            }

            // ---- 走到这里说明前台应用确实变了 ----

            // 正在观察中：不接管、不重启。继续观察原来那个应用，
            // 靠多窗口扫描去覆盖广告窗口，避免把观察窗口拖到正常界面上。
            if (ctx.watchingPkg != null) return FG_KEEP;

            // 没在观察，而且新应用也不需要监听（桌面/设置/系统UI）：
            // 什么都不做；Service 会记下「见过系统窗口」。
            if (!shouldWatch(pkg)) return FG_IGNORE;

            // 没在观察，新应用需要监听 —— 但还要过冷却这一关：
            // 刚从同一个应用观察完、且期间没回过桌面/系统窗口，
            // 说明这多半只是该应用内部的界面切换（例如从广告页跳到主页），
            // 不能再来一轮，否则就是在正常界面上乱找按钮。
            boolean sameAppAgain = pkg.equals(ctx.lastEpisodePkg);
            boolean withinCooldown = (ctx.now - ctx.lastEpisodeEndMs) < episodeCooldownMs;
            if (sameAppAgain && withinCooldown && !ctx.sawSystemSinceEpisode) {
                return FG_IGNORE;
            }

            return FG_START;
        }

        static final int FG_SAME = 0;    // 同一应用内变化，什么都不做
        static final int FG_KEEP = 1;    // 保持当前观察，不接管、不重启
        static final int FG_SWITCH = 2;  // 结束旧观察，改监听新应用（v3.0 起不再用于自动接管）
        static final int FG_START = 3;   // 当前没在观察，开始监听新应用
        static final int FG_IGNORE = 4;  // 什么都不做

        /** 组合该应用生效的文字规则。 */
        List<String> textsFor(String pkg) {
            AppRule ar = pkg == null ? null : apps.get(pkg);
            if (ar == null || ar.extraTexts.isEmpty()) return skipTexts;
            List<String> all = new ArrayList<String>(skipTexts);
            all.addAll(ar.extraTexts);
            return all;
        }

        /** 组合该应用生效的 ID 规则。 */
        List<String> idsFor(String pkg) {
            AppRule ar = pkg == null ? null : apps.get(pkg);
            if (ar == null || ar.extraIds.isEmpty()) return skipIds;
            List<String> all = new ArrayList<String>(skipIds);
            all.addAll(ar.extraIds);
            return all;
        }

        String nameOf(String pkg) {
            AppRule ar = pkg == null ? null : apps.get(pkg);
            return ar != null && ar.name != null && !ar.name.isEmpty() ? ar.name : pkg;
        }
    }
}
