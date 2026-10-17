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

            // ----------------------------------------------------------
            // v3.1「摇一摇广告」放宽（默认开）
            //
            // 问题：v3.0 的安全带只允许点顶部 30% 或底部 25%。但摇一摇广告
            // 把品牌 logo 和「摇一摇」提示放在最上面，于是把「跳过」按钮
            // **往下挤**（常见落在 30%~50% 一带）—— 正好被 v3.0 判成不安全，
            // 表现为「有摇一摇字样的广告就不跳了」。
            //
            // 修法：一旦在页面上发现摇一摇/摇动手机这类**广告特征文案**，
            // 就说明这确实是广告页、不是正常界面，于是把安全带放宽到
            // adTopBand / adBottomBand，但**中间永远留一条绝不点的走廊**。
            // ----------------------------------------------------------
            r.shakeAware = g.optBoolean("shakeAware", true);
            // 连跳过按钮的节点都没有时，是否允许在角落按坐标兜底点一次。
            // 默认关：这是唯一还会「凭坐标猜」的动作，风险最高，需用户显式开启。
            r.shakeBlindTap = g.optBoolean("shakeBlindTap", false);
            r.adTopBand = clampDouble(g.optDouble("adTopBand", 0.60), r.safeTopBand, 0.80);
            r.adBottomBand = clampDouble(g.optDouble("adBottomBand", 0.80), 0.30, r.safeBottomBand);
            // 保证顶部带与底部带之间始终留一条走廊，否则「放宽」就等于全屏可点。
            if (r.adBottomBand < r.adTopBand + 0.05) {
                r.adBottomBand = Math.min(0.99, r.adTopBand + 0.05);
            }
            r.shakeTapX = clampDouble(g.optDouble("shakeTapX", 0.92), 0.0, 1.0);
            r.shakeTapY = clampDouble(g.optDouble("shakeTapY", 0.055), 0.0, 1.0);
            // 摇一摇广告的「跳过」常做成一整条（文字 + 倒计时）的宽条，
            // 面积会超过 safeMaxArea。已确认是广告页时给一个更宽的上限。
            r.adMaxArea = clampDouble(g.optDouble("adMaxArea", 0.25), r.safeMaxArea, 0.55);
        }

        r.skipTexts = toList(root.optJSONArray("skipTexts"));
        r.strongTexts = toList(root.optJSONArray("strongTexts"));
        r.adTexts = toList(root.optJSONArray("adTexts"));
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

        // ---------------- v3.1 摇一摇广告放宽 ----------------

        /**
         * 摇一摇广告放宽（默认开）。
         *
         * 摇一摇广告会把品牌 logo 与「摇一摇」提示放在屏幕最上方，
         * 于是「跳过」被往下挤（常见落在 30%~55%），正好落在 v3.0 安全带之外，
         * 表现为「有摇一摇字样的广告不跳了」。开启后：一旦在页面上发现
         * 摇一摇/摇动手机这类广告特征文案，就改用更宽的安全带。
         */
        boolean shakeAware = true;
        /**
         * 检测到摇一摇广告时使用的安全带（比 safe 系列更宽）。
         * 两者之间那条走廊**永不点击** —— 放宽的前提是「知道这确实是广告页」，
         * 而不是「哪都能点」。
         *
         * 注意 adBottomBand 的语义是「底部带从哪开始」，它被钳制在
         * [0.30, safeBottomBand] 内 —— 也就是说它**只能往上挪**（值变小＝允许点击
         * 的范围向屏幕中部扩展），不能往下挪。默认 0.75 与普通模式一致，
         * 即底部带的放宽并无必要：真正的痛点在上方（跳过按钮被 logo 挤到 0.30~0.55）。
         */
        double adTopBand = 0.60;
        double adBottomBand = 0.75;
        /**
         * 已确认是广告页时放宽的面积上限。
         * 摇一摇广告的「跳过」常是「文字 + 倒计时」一整条宽条，比普通小按钮大。
         */
        double adMaxArea = 0.25;
        /** 摇一摇广告下，连跳过节点都没有时按坐标兜底点击的坐标（默认关、默认右上角）。 */
        boolean shakeBlindTap = false;
        double shakeTapX = 0.92;
        double shakeTapY = 0.055;

        /**
         * 判断某个候选在稳健模式下是否安全可点。
         *
         * 为什么需要它：抖音的「跳过」在右上角，而主页的**搜索图标也在右上角**，
         * 两者位置高度重合。仅靠文字/ID 匹配无法可靠区分，
         * 所以再加一层「形状与位置」的约束 —— 跳过按钮一定是小控件，
         * 且出现在屏幕顶部或底部，绝不落在正中央（那里是正文/输入区）。
         */
        /** 容器级面积上限：身份已确认的跳过按钮允许的最大面积（只挡住全屏容器）。 */
        static final double CONTAINER_AREA_CAP = 0.55;

        boolean isSafeCandidate(double areaRatio, double ny) {
            return isSafeCandidate(areaRatio, ny, false, false);
        }

        boolean isSafeCandidate(double areaRatio, double ny, boolean adSignal) {
            return isSafeCandidate(areaRatio, ny, adSignal, false);
        }

        /**
         * v3.2：带「广告特征」与「身份已确认」标志的安全判定。
         *
         * @param adSignal    本页已确认是摇一摇/互动等广告页（放宽安全带）。
         * @param identified 本候选是「身份已确认」的跳过按钮：精确命中 skipTexts，
         *                   或命中 skipIds。身份已确认意味着文字/ID 本身就是铁证，
         *                   因此面积上限放宽到容器级（只挡全屏容器），
         *                   但位置仍要求落在顶部/底部安全带内（不在正中乱点）。
         *                   这专门救「带倒计时的大『跳过』条」「广告 SDK 的关闭按钮」
         *                   这类被 v3.0/v3.1 面积上限误杀的控件 —— 用户反馈的
         *                   “有跳过但跳不掉”“互动广告关不了”正源于此。
         */
        boolean isSafeCandidate(double areaRatio, double ny, boolean adSignal, boolean identified) {
            if (!safeMode) return true;

            // 面积上限：身份已确认 > 广告页 > 普通（最严）。
            // 身份已确认时不看 safeMaxArea/adMaxArea，只挡真正的全屏容器
            // （面积 > CONTAINER_AREA_CAP 的节点是广告大图/视频容器，点了会跳转）。
            double areaCap = identified ? CONTAINER_AREA_CAP
                            : (adSignal && shakeAware ? adMaxArea : safeMaxArea);
            if (areaRatio > 0 && areaRatio > areaCap) return false;

            // 位置安全带：
            //  · 身份已确认（精确 skip 文字 / 命中 skip id）—— 文字或 ID 本身就是铁证，
            //    不再限制位置（否则「带倒计时的大『跳过』条」「SDK 关闭按钮」这类
            //    落在屏幕中部的控件仍会被挡掉，正是用户说的“有跳过但跳不掉”）。
            //  · 广告页（摇一摇等）—— 用更宽但仍有走廊的带。
            //  · 普通模式 —— 原始安全带（顶部/底部带，绝不点正中）。
            if (identified) return true;
            if (adSignal && shakeAware) {
                return ny <= adTopBand || ny >= adBottomBand;
            }
            return ny <= safeTopBand || ny >= safeBottomBand;
        }

        /**
         * 这段文字是不是「摇一摇广告」的特征文案。
         *
         * 用途不是决定「点哪里」，而是决定「要不要放宽安全带」。
         * 也就是说：只有先确认这确实是一张广告页，才敢让跳过按钮
         * 落在平时不允许的位置上。没有这个前提，放宽就等于失去防线。
         */
        boolean isAdSignal(String rawText) {
            if (rawText == null || rawText.isEmpty()) return false;
            String c = compactForAd(rawText);
            if (c.isEmpty()) return false;
            for (String k : adTexts) {
                String kc = compactForAd(k);
                if (!kc.isEmpty() && c.contains(kc)) return true;
            }
            return false;
        }

        /** 与 SkipEngine.compact 同样的压缩（去空白与标点），用于广告特征比对。 */
        private static String compactForAd(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (Character.isWhitespace(ch)) continue;
                if (Character.isLetterOrDigit(ch)) sb.append(ch);
            }
            return sb.toString().toLowerCase(java.util.Locale.ROOT);
        }

        List<String> skipTexts = new ArrayList<String>();
        List<String> strongTexts = new ArrayList<String>();
        List<String> denyTexts = new ArrayList<String>();
        List<String> skipIds = new ArrayList<String>();
        /** v3.1：摇一摇等广告特征文案，命中即放宽安全带（不直接点击）。 */
        List<String> adTexts = new ArrayList<String>();
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

            // 正在观察中：区分两种「别的包名」——
            //  · 用户真的离开了应用（回到桌面/设置/系统UI 等排除名单内的包）：
            //    立即结束观察，绝不在后台或后续界面上继续点。这正是不少用户反馈的
            //    「退出以后会反复点回去」的根因：人已经走了，观察窗口却还挂着。
            //  · 广告 SDK 的独立窗口（不在排除名单）：保持锚定在原来那个应用上，
            //    靠多窗口扫描去覆盖它，避免把观察窗口拖到正常界面上。
            if (ctx.watchingPkg != null) {
                if (!shouldWatch(pkg)) return FG_LEAVE;
                return FG_KEEP;
            }

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
        static final int FG_LEAVE = 5;   // 用户已离开应用（回到桌面/设置/系统UI），立即结束观察（v3.3）

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
