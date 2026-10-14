package com.lbx.skipad;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 核心识别与点击逻辑。
 *
 * 设计原则：宁可不点，不可错点。
 *  - 只在“应用刚启动的前若干秒”这个时间窗内工作（开屏广告只会出现在这个窗口）；
 *  - 文字必须短且能压缩成已知关键词，否则不点；
 *  - 命中黑名单文字不点；
 *  - 输入框内不点。
 */
final class SkipEngine {

    private SkipEngine() {}

    private static final int MAX_NODES = 800;
    /** 从命中节点向上最多回溯多少层找可点击祖先。 */
    private static final int MAX_ANCESTOR_UP = 8;

    static final class Hit {
        final AccessibilityNodeInfo node;
        final String reason;

        Hit(AccessibilityNodeInfo node, String reason) {
            this.node = node;
            this.reason = reason;
        }
    }

    // ------------------------------------------------------------------ 文本归一化

    /**
     * 去掉空白与所有标点，仅保留字母数字，用于长度判断。
     * "跳过广告 5s" -> "跳过广告5s"
     */
    static String compact(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (Character.isLetterOrDigit(c)) sb.append(c);
            // 其它（标点、Emoji、符号）一律丢掉
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    static String norm(CharSequence cs) {
        if (cs == null) return "";
        return cs.toString().replace('\u00A0', ' ').trim();
    }

    // ------------------------------------------------------------------ 匹配

    static boolean textMatches(String raw, RuleStore.Rules r, List<String> texts, List<String> strong) {
        if (raw == null || raw.isEmpty()) return false;
        String compact = compact(raw);
        if (compact.isEmpty()) return false;

        // 1) 黑名单优先：完全相等，或黑名单词较长且被包含
        //    （例："跳过此步骤" 能拦住 "跳过此步骤详情"，而短词如"取消"只做完全匹配，避免误伤）
        for (String d : r.denyTexts) {
            String dc = compact(d);
            if (dc.isEmpty()) continue;
            if (compact.equals(dc)) return false;
            if (dc.length() >= 3 && compact.contains(dc)) return false;
        }

        // 2) 精确匹配（用户配置的完整文字，忽略大小写/空白/标点）
        for (String s : texts) {
            String sc = compact(s);
            if (!sc.isEmpty() && compact.equals(sc)) return true;
        }

        // 3) 强关键词包含匹配：仅当压缩后足够短
        if (compact.length() <= r.maxTextLen) {
            for (String k : strong) {
                String kc = compact(k);
                if (!kc.isEmpty() && compact.contains(kc)) return true;
            }
        }
        return false;
    }

    /**
     * 控件 ID 匹配。
     *
     * Android 的 viewIdResourceName 形如 "com.tencent.mm:id/skip_btn"，
     * 拆成 包名 / id / 名字 三段后按下述规则比较：
     *   "*:xxx"      —— 拿 xxx 与「名字」做通配匹配（推荐写法，换应用也能用）
     *   含 "/"        —— 与完整 ID 做通配匹配
     *   其它          —— 与「名字」做通配匹配
     * 通配符 * 表示任意长度字符。
     */
    static boolean idMatches(String viewId, List<String> patterns) {
        if (viewId == null || viewId.isEmpty() || patterns == null) return false;

        int colon = viewId.indexOf(':');
        String rest = colon >= 0 ? viewId.substring(colon + 1) : viewId;
        int slash = rest.indexOf('/');
        String name = slash >= 0 ? rest.substring(slash + 1) : rest;

        for (String p : patterns) {
            if (p == null || p.isEmpty()) continue;

            if (p.startsWith("*:")) {
                if (glob(p.substring(2), name)) return true;
            } else if (p.indexOf('/') >= 0) {
                if (glob(p, viewId)) return true;
            } else {
                if (glob(p, name)) return true;
            }
        }
        return false;
    }

    /** 极简通配匹配，只支持 * （经典双指针算法，无回溯开销）。 */
    static boolean glob(String pat, String s) {
        if (pat == null || s == null) return false;
        int p = 0;
        int i = 0;
        int star = -1;
        int mark = 0;

        while (i < s.length()) {
            if (p < pat.length() && pat.charAt(p) == s.charAt(i)) {
                p++;
                i++;
            } else if (p < pat.length() && pat.charAt(p) == '*') {
                star = p++;
                mark = i;
            } else if (star >= 0) {
                p = star + 1;
                i = ++mark;
            } else {
                return false;
            }
        }
        while (p < pat.length() && pat.charAt(p) == '*') p++;
        return p == pat.length();
    }

    private static boolean isEditable(AccessibilityNodeInfo n) {
        try {
            if (n.isEditable()) return true;
            if (n.isPassword()) return true;
            CharSequence cn = n.getClassName();
            if (cn != null) {
                String c = cn.toString();
                if (c.contains("EditText") || c.contains("AutoCompleteTextView")
                        || c.contains("SearchView") || c.contains("WebView")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ------------------------------------------------------------------ 扫描

    /**
     * 在窗口树里找跳过按钮。返回 null 表示没找到。
     */
    static Hit find(AccessibilityNodeInfo root, RuleStore.Rules r, String pkg) {
        if (root == null) return null;

        List<String> texts = r.textsFor(pkg);
        List<String> ids = r.idsFor(pkg);

        // 注意：从 API 33 起 AccessibilityNodeInfo.recycle() 已废弃且为空操作，
        // 手动回收反而容易造成 use-after-free 崩溃，这里交给 GC 处理。
        List<AccessibilityNodeInfo> stack = new ArrayList<AccessibilityNodeInfo>();
        stack.add(root);

        Hit hit = null;
        int seen = 0;

        while (!stack.isEmpty() && seen < MAX_NODES) {
            AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
            if (n == null) continue;
            seen++;

            String viewId = null;
            try {
                viewId = n.getViewIdResourceName();
            } catch (Throwable ignored) {
            }

            String txt = norm(n.getText());
            String desc = norm(n.getContentDescription());

            boolean byId = idMatches(viewId, ids);
            boolean byText = textMatches(txt, r, texts, r.strongTexts)
                    || textMatches(desc, r, texts, r.strongTexts);

            if ((byId || byText) && !isEditable(n)) {
                String reason = byId
                        ? ("控件ID " + shortId(viewId))
                        : ("文字 “" + (txt.isEmpty() ? desc : txt) + "”");
                hit = new Hit(n, reason);
                break;
            }

            int c = 0;
            try {
                c = n.getChildCount();
            } catch (Throwable ignored) {
            }
            for (int i = c - 1; i >= 0; i--) {
                AccessibilityNodeInfo ch = null;
                try {
                    ch = n.getChild(i);
                } catch (Throwable ignored) {
                }
                if (ch != null) {
                    stack.add(ch);
                }
            }
        }

        return hit;
    }

    static String shortId(String viewId) {
        if (viewId == null) return "";
        int i = viewId.indexOf('/');
        return i >= 0 ? viewId.substring(i + 1) : viewId;
    }

    // ------------------------------------------------------------------ 点击

    /**
     * 点击「跳过」所在的位置。
     *
     * 顺序刻意如此，是为了避免点进广告：
     * 很多开屏页里整个广告容器都是可点击的（点了就跳转广告落地页），
     * 而「跳过」文字只是它的子节点。若一上来就往上找可点击祖先，
     * 很可能点到广告本身，所以优先用文字节点自己的坐标精准点击。
     *  1) 节点自身可点击 -> ACTION_CLICK
     *  2) 节点自身有有效坐标 -> 在它中心做坐标点击（最精准）
     *  3) 都没有 -> 再逐层向上找可点击祖先 -> ACTION_CLICK / 坐标点击
     */
    @SuppressWarnings("deprecation")
    static boolean clickNode(AccessibilityService svc, AccessibilityNodeInfo n) {
        if (n == null) return false;

        // 1) 自身可点击
        try {
            if (n.isClickable() && n.isEnabled()
                    && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        // 2) 自身坐标精准点击
        if (hasBounds(n) && tap(svc, n)) return true;

        // 3) 向上找可点击祖先兜底
        AccessibilityNodeInfo t = n;
        for (int i = 0; i < MAX_ANCESTOR_UP && t != null; i++) {
            AccessibilityNodeInfo parent = null;
            try {
                parent = t.getParent();
            } catch (Throwable ignored) {
            }
            if (parent == null) break;
            try {
                if (parent.isClickable() && parent.isEnabled()
                        && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            if (hasBounds(parent) && tap(svc, parent)) return true;
            t = parent;
        }
        return false;
    }

    private static boolean hasBounds(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        try {
            // 注意：getBoundsInScreen(Rect) 返回 void，不是 boolean
            n.getBoundsInScreen(r);
        } catch (Throwable t) {
            return false;
        }
        return r.width() > 0 && r.height() > 0;
    }

    /** 在节点中心补一次真实手势点击。 */
    static boolean tap(AccessibilityService svc, AccessibilityNodeInfo n) {
        Rect r = new Rect();
        try {
            n.getBoundsInScreen(r);
        } catch (Throwable t) {
            return false;
        }
        if (r.width() <= 0 || r.height() <= 0) return false;

        float x = r.exactCenterX();
        float y = r.exactCenterY();

        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(p, 0, 45);
            GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
            return svc.dispatchGesture(g, null, null);
        } catch (Throwable t) {
            return false;
        }
    }
}
