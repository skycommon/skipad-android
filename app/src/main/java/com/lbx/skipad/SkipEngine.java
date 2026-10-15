package com.lbx.skipad;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 核心识别与点击逻辑（v2）。
 *
 * v1 只用 getRootInActiveWindow() 扫当前活动窗口，这是 QQ音乐 这类 App
 * 跳不掉的主要原因：开屏广告常常由广告 SDK 渲染在**独立的窗口**里，
 * 它不在活动窗口的节点树内，所以永远扫不到。
 * v2 改为遍历 getWindows() 的全部窗口。
 *
 * 其它改进：
 *  - 候选打分：不再「命中第一个就点」，而是收集多个候选后挑最像跳过按钮的那个；
 *  - 剔除大块广告容器：面积超过屏幕 55% 的节点不可能是「跳过」小按钮；
 *  - 更全面的默认控件 ID / 文案（见 default_rules.json）。
 *
 * 设计原则不变：宁可不点，不可错点。
 */
final class SkipEngine {

    private SkipEngine() {}

    /** 单次扫描最多看的节点数（所有窗口加起来）。 */
    static final int MAX_NODES = 2000;
    /** 从命中节点向上最多回溯多少层找可点击祖先（真正点击时）。 */
    private static final int MAX_ANCESTOR_UP = 8;
    /** 打分时向上探查可点击祖先的层数（比点击时浅，够判断即可）。 */
    private static final int CLICK_PROBE_UP = 3;
    /** 面积超过屏幕这个比例的节点视为广告容器，不做候选。 */
    private static final double MAX_AREA_RATIO = 0.55;
    /** 点击前复核时允许的位置漂移（像素）。 */
    private static final int TAP_TOLERANCE_PX = 8;
    /** 复核坐标命中时向下搜索的层数上限。 */
    private static final int MAX_TAP_SEARCH_DEPTH = 40;

    // ------------------------------------------------------------------ 打分权重
    // 全部是纯常量，配合 Cand 里的纯数据字段，可以在桌面 JVM 上直接单测。

    static final int S_ID        = 60;
    static final int S_EXACT     = 80;
    static final int S_STRONG    = 40;
    static final int S_CLICKABLE = 25;
    static final int S_ANC_1     = 15;
    static final int S_ANC_2     = 8;
    static final int S_SMALL     = 20;
    static final int S_TOP       = 12;
    static final int S_RIGHT     = 10;
    static final int S_BOTTOM    = 8;
    static final int S_COUNTDOWN = 8;

    /** 低于这个分数的候选直接放弃，避免"矮子里拔将军"点错。 */
    static final int MIN_SCORE = 30;

    // ------------------------------------------------------------------ 候选

    /**
     * 一个候选跳过按钮。
     * 除 node 外的字段都是纯数据，便于脱离安卓环境单测打分逻辑。
     */
    static final class Cand {
        AccessibilityNodeInfo node;
        String reason = "";

        boolean idHit;
        boolean exactHit;
        boolean strongHit;
        boolean clickable;
        /** 向上几层能找到可点击祖先；-1 表示 {@link #CLICK_PROBE_UP} 层内没有。 */
        int clickableDepth = -1;
        /** 占窗口面积比例 0~1。 */
        double areaRatio;
        /** 归一化中心点，0~1。 */
        double nx = 0.5;
        double ny = 0.5;
        /** 是否含数字（倒计时，如"跳过5"）。 */
        boolean hasDigit;

        /**
         * v3.0 稳健模式判定结果：这个候选是否「安全可点」。
         *
         * 抖音的「跳过」在右上角，而主页**搜索图标也在右上角**，位置高度重合。
         * 仅靠文字/ID 无法可靠区分，所以由 RuleStore.Rules.isSafeCandidate()
         * 再加一层「小而靠边」的形状约束：太大、或落在屏幕正中的都不算安全。
         * 默认 false，由 collect() 按规则判定后置位 —— 这样万一漏设，
         * 结果是「不点」而不是「乱点」。
         */
        boolean safe;

        /**
         * 是否由**文字**命中（而不是只靠控件 ID）。
         *
         * 稳健模式下要求候选必须有文字依据：跳过按钮上写着「跳过 / 跳过广告 / 跳过5」，
         * 而抖音主页的搜索图标通常是**纯图标**（没有文字，或只有 contentDescription
         * 写着"搜索"）。仅靠 `*:*skip*` 这种 ID 规则更容易误伤，所以稳健模式下
         * 不给纯 ID 候选点击机会。
         */
        boolean textHit;

        /**
         * v3.1：本候选所在**页面上**发现了摇一摇/摇动手机等广告特征文案。
         *
         * 为什么按「页面」而不是按「节点」判断：摇一摇那句提示和「跳过」
         * 按钮是两个不相干的节点（甚至不在同一个窗口里）。只有把整页
         * 看一遍，才知道这是广告页，从而敢放宽安全带。
         */
        boolean adSignal;

        /**
         * v3.2：本候选是否为「身份已确认」的跳过按钮。
         *
         * 满足其一即为真：精确命中 skipTexts（kind==2）或命中 skipIds。
         * 这两类本就是跳过的铁证，因此放宽安全带时只对它们彻底解开
         * 面积上限与位置约束（仍需排除全屏广告容器，避免点进落地页）：
         * 这是救「带倒计时的大『跳过』条」「广告 SDK 的关闭按钮」「互动广告关闭键」
         * 的关键 —— 它们常是大块、且落在屏幕中部，被 v3.0/v3.1 的形状约束误杀。
         */
        boolean identified;

        /** 综合打分。纯函数，不碰 node。 */
        int score() {
            int s = 0;
            if (idHit) s += S_ID;
            if (exactHit) s += S_EXACT;
            if (strongHit) s += S_STRONG;
            if (clickable) s += S_CLICKABLE;
            if (clickableDepth == 1) s += S_ANC_1;
            else if (clickableDepth == 2) s += S_ANC_2;

            // 按钮通常不大：面积占比越小越像按钮
            if (areaRatio > 0 && areaRatio <= 0.06) s += S_SMALL;
            else if (areaRatio > 0 && areaRatio <= 0.15) s += S_SMALL / 2;

            // 常见位置：右上角、右上偏下、底部
            if (ny <= 0.18) s += S_TOP;
            if (nx >= 0.62) s += S_RIGHT;
            if (ny >= 0.80) s += S_BOTTOM;

            if (hasDigit) s += S_COUNTDOWN;
            return s;
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
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    static String norm(CharSequence cs) {
        if (cs == null) return "";
        return cs.toString().replace('\u00A0', ' ').trim();
    }

    static boolean hasDigit(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 匹配

    /** 0=不匹配，1=强关键词包含，2=精确匹配。 */
    static int matchKind(String raw, RuleStore.Rules r, List<String> texts, List<String> strong) {
        if (raw == null || raw.isEmpty()) return 0;
        String c = compact(raw);
        if (c.isEmpty()) return 0;

        // 1) 黑名单优先：完全相等，或黑名单词较长且被包含
        for (String d : r.denyTexts) {
            String dc = compact(d);
            if (dc.isEmpty()) continue;
            if (c.equals(dc)) return 0;
            if (dc.length() >= 3 && c.contains(dc)) return 0;
        }

        // 2) 精确匹配
        for (String s : texts) {
            String sc = compact(s);
            if (!sc.isEmpty() && c.equals(sc)) return 2;
        }

        // 3) 强关键词包含匹配：仅当压缩后足够短
        if (c.length() <= r.maxTextLen) {
            for (String k : strong) {
                String kc = compact(k);
                if (!kc.isEmpty() && c.contains(kc)) return 1;
            }
        }
        return 0;
    }

    /** 兼容旧签名的布尔版本。 */
    static boolean textMatches(String raw, RuleStore.Rules r, List<String> texts, List<String> strong) {
        return matchKind(raw, r, texts, strong) > 0;
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
                        || c.contains("SearchView")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 从 n 自身向上找可点击节点，返回层数；0=自身可点击，-1=没找到。 */
    private static int clickableDepthOf(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo t = n;
        for (int d = 0; d <= CLICK_PROBE_UP && t != null; d++) {
            try {
                if (t.isClickable() && t.isEnabled()) return d;
            } catch (Throwable ignored) {
            }
            if (d == CLICK_PROBE_UP) break;
            try {
                t = t.getParent();
            } catch (Throwable ignored) {
                return -1;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ 扫描

    /**
     * 扫描一棵窗口树，把命中的节点收集为候选。
     *
     * @param winW / winH 该窗口的宽高，用于算面积占比与归一化坐标
     */
    static void collect(AccessibilityNodeInfo root, int winW, int winH,
                        RuleStore.Rules r, String pkg, List<Cand> out, int[] budget,
                        boolean adSignal) {
        if (root == null) return;

        List<String> texts = r.textsFor(pkg);
        List<String> ids = r.idsFor(pkg);

        // 注意：API 33 起 AccessibilityNodeInfo.recycle() 已废弃且为空操作，
        // 手动回收反而容易 use-after-free，这里交给 GC。
        List<AccessibilityNodeInfo> stack = new ArrayList<AccessibilityNodeInfo>();
        stack.add(root);

        Rect rect = new Rect();

        // budget[0] 是所有窗口共享的节点预算，每访问一个节点减一。
        // 注意不要再用一个独立的计数器去跟它比较：
        // 「seen++ 的同时 budget[0]--」会让 seen + budget 恒定不变，
        // 循环提前在预算一半时就结束。这里只以 budget 为准。
        while (!stack.isEmpty() && budget[0] > 0) {
            AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
            if (n == null) continue;
            budget[0]--;

            String viewId = null;
            try {
                viewId = n.getViewIdResourceName();
            } catch (Throwable ignored) {
            }

            String txt = norm(n.getText());
            String desc = norm(n.getContentDescription());

            int kindText = matchKind(txt, r, texts, r.strongTexts);
            int kindDesc = matchKind(desc, r, texts, r.strongTexts);
            int kind = Math.max(kindText, kindDesc);
            boolean byId = idMatches(viewId, ids);

            if ((byId || kind > 0) && !isEditable(n)) {
                int w = 0;
                int h = 0;
                try {
                    n.getBoundsInScreen(rect);   // 返回 void
                    w = rect.width();
                    h = rect.height();
                } catch (Throwable ignored) {
                }

                double areaRatio = 0.0;
                if (winW > 0 && winH > 0 && w > 0 && h > 0) {
                    areaRatio = (double) w * h / ((double) winW * winH);
                }

                // 太大的节点不可能是「跳过」小按钮，直接不候选
                if (areaRatio <= MAX_AREA_RATIO) {
                    Cand c = new Cand();
                    c.node = n;
                    c.idHit = byId;
                    c.exactHit = kind == 2;
                    c.strongHit = kind == 1;
                    c.hasDigit = hasDigit(txt) || hasDigit(desc);
                    c.areaRatio = areaRatio;

                    if (winW > 0 && winH > 0 && w > 0 && h > 0) {
                        c.nx = rect.exactCenterX() / winW;
                        c.ny = rect.exactCenterY() / winH;
                    }

                    try {
                        c.clickable = n.isClickable() && n.isEnabled();
                    } catch (Throwable ignored) {
                    }
                    c.clickableDepth = clickableDepthOf(n);

                    // v3.0：稳健模式下按「文字依据 + 形状 + 位置」判定是否安全可点
                    // v3.1：若本页已确认是摇一摇广告，则改用放宽的安全带，
                    //       并**免除**「文字依据」闸门（摇一摇提示本身就是页面证据）。
                    // v3.2：身份已确认（精确 skip 文字命中 或 命中 skip id）的候选视为铁证，
                    //       免面积上限、免位置安全带、免「文字依据」闸门 —— 专门救
                    //       「带倒计时的大『跳过』条」「SDK 关闭按钮」「互动广告的关闭键」
                    //       这类被面积/位置误杀的控件（用户反馈：有跳过却跳不掉、互动广告关不了）。
                    c.textHit = kind > 0;
                    c.adSignal = adSignal;
                    // 身份已确认 = 精确文字匹配（kind==2）或命中控件 ID。
                    // 这两类本身就是跳过的铁证，不再被「太小/太靠边」约束。
                    c.identified = c.exactHit || c.idHit;
                    boolean relaxed = adSignal && r.shakeAware;
                    c.safe = r.isSafeCandidate(areaRatio, c.ny, adSignal, c.identified)
                            && (!r.safeMode || c.textHit || c.idHit || relaxed);

                    String shown = !txt.isEmpty() ? txt : desc;
                    if (byId) {
                        c.reason = "控件ID " + shortId(viewId)
                                + (shown.isEmpty() ? "" : "（" + shown + "）");
                    } else {
                        c.reason = "文字 “" + shown + "”";
                    }
                    out.add(c);
                }
            }

            int cn = 0;
            try {
                cn = n.getChildCount();
            } catch (Throwable ignored) {
            }
            for (int i = cn - 1; i >= 0; i--) {
                AccessibilityNodeInfo ch = null;
                try {
                    ch = n.getChild(i);
                } catch (Throwable ignored) {
                }
                if (ch != null) stack.add(ch);
            }
        }
    }

    /**
     * 从候选里挑分最高的；低于 MIN_SCORE 返回 null。
     *
     * v3.0：若存在「安全」候选，就只在安全候选里挑；一个都没有时，
     * 才退而返回分最高的不安全候选（由调用方决定拦下并记日志）。
     * 这样既能保持诊断可见性，又不会因为一个高分的不安全候选
     * 而把一个真正可用的安全候选挤掉。
     */
    static Cand pickBest(List<Cand> cands) {
        Cand bestSafe = null;
        Cand bestAny = null;
        for (Cand c : cands) {
            if (c.score() < MIN_SCORE) continue;
            if (bestAny == null || c.score() > bestAny.score()) bestAny = c;
            if (c.safe) {
                if (bestSafe == null || c.score() > bestSafe.score()) bestSafe = c;
            }
        }
        return bestSafe != null ? bestSafe : bestAny;
    }

    /**
     * 遍历所有窗口找跳过按钮。返回 null 表示没找到。
     *
     * v3.1 起是**两趟**扫描：
     *   第 1 趟：只看文字，判断本页有没有摇一摇/摇动手机这类广告特征
     *            （adSignal）。这一步不产生候选，只回答一个是非题。
     *   第 2 趟：带着这个结论去收集候选 —— 因为「跳过按钮允不允许出现在
     *            靠中间的位置」这件事，取决于第 1 趟的答案。
     * 两趟的顺序不能颠倒：先知道是广告页，才敢放宽；反过来就成了盲放宽。
     *
     * @param roots 各窗口的根节点（由服务层用 getWindows() 收集）
     */
    static Cand findBest(List<AccessibilityNodeInfo> roots, List<int[]> sizes,
                         RuleStore.Rules r, String pkg) {
        if (roots == null || roots.isEmpty()) return null;

        List<Cand> cands = new ArrayList<Cand>();

        // ---- 第 1 趟：页面级广告特征探测 ----
        boolean adSignal = false;
        if (r.shakeAware) {
            int[] probe = new int[]{MAX_NODES / 2};
            for (int i = 0; i < roots.size() && !adSignal; i++) {
                adSignal = hasAdSignal(roots.get(i), r, probe);
            }
        }

        // ---- 第 2 趟：收集候选 ----
        int[] budget = new int[]{MAX_NODES};

        for (int i = 0; i < roots.size(); i++) {
            if (budget[0] <= 0) break;
            int w = 0;
            int h = 0;
            if (sizes != null && i < sizes.size() && sizes.get(i) != null) {
                w = sizes.get(i)[0];
                h = sizes.get(i)[1];
            }
            collect(roots.get(i), w, h, r, pkg, cands, budget, adSignal);
        }
        return pickBest(cands);
    }

    /**
     * 判断这棵窗口树里有没有「摇一摇广告」的特征文案。
     *
     * 只看文字与 contentDescription，不产生任何候选 —— 它的唯一作用是
     * 让后续的安全判定知道「这确实是广告页」。因此宁可漏判（不放宽、
     * 回到 v3.0 的保守行为），也不误判（否则等于凭空放宽防线）。
     */
    static boolean hasAdSignal(AccessibilityNodeInfo root, RuleStore.Rules r, int[] budget) {
        if (root == null || r.adTexts.isEmpty()) return false;

        List<AccessibilityNodeInfo> stack = new ArrayList<AccessibilityNodeInfo>();
        stack.add(root);

        while (!stack.isEmpty() && budget[0] > 0) {
            AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
            if (n == null) continue;
            budget[0]--;

            try {
                if (r.isAdSignal(norm(n.getText()))) return true;
                if (r.isAdSignal(norm(n.getContentDescription()))) return true;
            } catch (Throwable ignored) {
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
                if (ch != null) stack.add(ch);
            }
        }
        return false;
    }

    static String shortId(String viewId) {
        if (viewId == null) return "";
        int i = viewId.indexOf('/');
        return i >= 0 ? viewId.substring(i + 1) : viewId;
    }

    // ------------------------------------------------------------------ 点击

    /**
     * 点击「跳过」所在的位置（v3.0 加固）。
     *
     * 顺序刻意如此，是为了避免点进广告：
     * 很多开屏页里整个广告容器都是可点击的（点了就跳转广告落地页），
     * 而「跳过」文字只是它的子节点。若一上来就往上找可点击祖先，
     * 很可能点到广告本身，所以优先用文字节点自己的坐标精准点击。
     *
     * v3.0 的一处关键修改：**优先用 ACTION_CLICK，而不是坐标手势。**
     * `dispatchGesture` 是异步的 —— 手势真正落下时，屏幕内容可能已经变了
     * （开屏页切到主界面）。此时同一坐标上正好出现了别的东西
     * （抖音右上角的搜索图标就在「跳过」原来的位置），于是就被点中了。
     * ACTION_CLICK 是直接作用在那个节点上的，不受这种位移影响。
     * 所以：
     *  1) 节点自身可点击 -> ACTION_CLICK（首选，最安全）
     *  2) 节点自身有有效坐标 -> 用【复核过的】坐标点击（见 tapVerified）
     *  3) 都没有 -> 向上找可点击祖先 -> ACTION_CLICK
     * 注意：v3.0 不再对祖先做坐标兜底点击 —— 盲点祖先的误触代价太大。
     */
    @SuppressWarnings("deprecation")
    static boolean clickNode(AccessibilityService svc, AccessibilityNodeInfo n, boolean verify) {
        if (n == null) return false;

        // 1) 首选：直接对该节点执行 ACTION_CLICK
        try {
            if (n.isClickable() && n.isEnabled()
                    && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        // 2) 次选：坐标点击自身（verify 为真时先复核目标仍原位）
        boolean tapped = verify ? tapVerified(svc, n) : (hasBounds(n) && tap(svc, n));
        if (tapped) return true;

        // 3) 兜底：向上找可点击祖先，并直接对祖先 ACTION_CLICK
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
            t = parent;
        }
        return false;
    }

    /**
     * 点击前复核：目标节点在**此刻**是否仍然真实存在于屏幕上的那个位置。
     *
     * 开屏广告消失得很快，而 dispatchGesture 从发出到真正落下有延迟。
     * 如果在这段间隙里界面已经切换，同一坐标上可能已经是另一个控件
     * （抖音的搜索图标就常出现在「跳过」原来的位置），于是造成误触。
     *
     * 复核方式：
     *   · 节点必须仍然 attached、enabled、可见；
     *   · 它的屏幕矩形必须与刚才一致（容差 8px）；
     *   · 该坐标处实际存在的节点，必须与它就是同一个（或其后代）。
     * 任何一条不满足就放弃 —— 宁可漏跳，不可误触。
     */
    @SuppressWarnings("deprecation")
    static boolean tapVerified(AccessibilityService svc, AccessibilityNodeInfo n) {
        Rect r0 = new Rect();
        try {
            n.getBoundsInScreen(r0);
        } catch (Throwable t) {
            return false;
        }
        if (r0.width() <= 0 || r0.height() <= 0) return false;

        boolean ok = stillSameNode(svc, n, r0);
        if (!ok) return false;

        float x = r0.exactCenterX();
        float y = r0.exactCenterY();
        return tapAt(svc, x, y);
    }

    /**
     * 确认节点此刻仍然在原位，且那个坐标上确实还是它。
     * 独立出来是为了可读性与可测性（只依赖少量 Android API）。
     */
    @SuppressWarnings("deprecation")
    static boolean stillSameNode(AccessibilityService svc, AccessibilityNodeInfo n, Rect expect) {
        try {
            if (!n.isEnabled()) return false;
        } catch (Throwable ignored) {
            return false;
        }

        Rect now = new Rect();
        try {
            n.getBoundsInScreen(now);
        } catch (Throwable t) {
            return false;
        }
        // 位置变了说明界面已经重排，放弃
        if (Math.abs(now.left - expect.left) > TAP_TOLERANCE_PX
                || Math.abs(now.top - expect.top) > TAP_TOLERANCE_PX
                || Math.abs(now.right - expect.right) > TAP_TOLERANCE_PX
                || Math.abs(now.bottom - expect.bottom) > TAP_TOLERANCE_PX) {
            return false;
        }

        // 该坐标处当前实际存在的节点，必须就是它（或它的后代）
        try {
            List<AccessibilityWindowInfo> wins = svc.getWindows();
            if (wins == null) return false;
            int cx = now.centerX();
            int cy = now.centerY();
            for (AccessibilityWindowInfo w : wins) {
                if (w == null) continue;
                AccessibilityNodeInfo root = null;
                try {
                    root = w.getRoot();
                } catch (Throwable ignored) {
                }
                if (root == null) continue;
                AccessibilityNodeInfo hit = findNodeAt(root, cx, cy, 0);
                if (hit != null) {
                    return isSameOrDescendantOf(hit, n);
                }
            }
            return false;
        } catch (Throwable t) {
            // 复核本身出错时保守处理：不点
            return false;
        }
    }

    /** 在窗口树里找位于 (x,y) 的最深层节点。 */
    @SuppressWarnings("deprecation")
    private static AccessibilityNodeInfo findNodeAt(AccessibilityNodeInfo n, int x, int y, int depth) {
        if (n == null || depth > MAX_TAP_SEARCH_DEPTH) return null;

        Rect r = new Rect();
        try {
            n.getBoundsInScreen(r);
        } catch (Throwable t) {
            return null;
        }
        if (!r.contains(x, y)) return null;

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
            if (ch == null) continue;
            AccessibilityNodeInfo deeper = findNodeAt(ch, x, y, depth + 1);
            if (deeper != null) return deeper;
        }
        return n;
    }

    /** a 是否就是 b，或者 b 的后代。 */
    @SuppressWarnings("deprecation")
    private static boolean isSameOrDescendantOf(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        AccessibilityNodeInfo t = a;
        for (int i = 0; i < MAX_ANCESTOR_UP + 4 && t != null; i++) {
            AccessibilityNodeInfo p = null;
            try {
                p = t.getParent();
            } catch (Throwable ignored) {
            }
            if (p == null) return false;
            if (p.equals(b)) return true;
            t = p;
        }
        return false;
    }

    private static boolean hasBounds(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        try {
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
        return tapAt(svc, r.exactCenterX(), r.exactCenterY());
    }

    /** 在屏幕指定坐标做一次点击。 */
    static boolean tapAt(AccessibilityService svc, float x, float y) {
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

    /**
     * 兜底：按归一化坐标盲点一次（默认右上角）。
     * 仅在开启「盲点兜底」且本窗口完全找不到任何候选时才会被调用。
     */
    static boolean tapNormalized(AccessibilityService svc, int w, int h,
                                 double nx, double ny) {
        if (w <= 0 || h <= 0) return false;
        return tapAt(svc, (float) (w * nx), (float) (h * ny));
    }
}
