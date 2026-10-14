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
