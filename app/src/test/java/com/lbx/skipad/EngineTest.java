package com.lbx.skipad;

import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 纯逻辑自测：在桌面 JVM 上验证规则解析与匹配算法，不需要安卓设备。
 * 只调用不触碰 Android API 的静态方法。
 */
public class EngineTest {

    private static int pass = 0;
    private static int fail = 0;

    private static void check(String name, boolean cond) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("  [FAIL] " + name);
        }
    }

    private static void eq(String name, Object got, Object want) {
        boolean ok = (got == null) ? want == null : got.equals(want);
        if (!ok) {
            fail++;
            System.out.println("  [FAIL] " + name + "  got=" + got + "  want=" + want);
        } else {
            pass++;
        }
    }

    public static void main(String[] args) throws Exception {
        String raw = readResource("/default_rules.json");
        RuleStore.Rules r = RuleStore.parse(raw);

        System.out.println("== 规则解析 ==");
        check("skipTexts 非空", !r.skipTexts.isEmpty());
        check("strongTexts 非空", !r.strongTexts.isEmpty());
        check("skipIds 非空", !r.skipIds.isEmpty());
        check("excludeApps 非空", !r.excludeApps.isEmpty());
        eq("scanIntervalMs", r.scanIntervalMs, 100);
        eq("maxTextLen", r.maxTextLen, 8);
        eq("maxWaitMs", r.maxWaitMs, 15000);
        eq("retryLimit", r.retryLimit, 8);
        eq("watchAll", r.watchAll, true);

        System.out.println("== 文本归一化 ==");
        eq("compact 去空白标点", SkipEngine.compact("跳过广告 5s"), "跳过广告5s");
        eq("compact 小写化", SkipEngine.compact("SKIP AD"), "skipad");
        eq("compact 去中文标点", SkipEngine.compact("跳过\n广告|5"), "跳过广告5");
        eq("compact 空", SkipEngine.compact(null), "");
        eq("norm 去 NBSP", SkipEngine.norm("\u00A0跳过\u00A0"), "跳过");

        List<String> texts = r.textsFor(null);
        List<String> strong = r.strongTexts;

        System.out.println("== 正向：应该跳过 ==");
        check("精确 “跳过”", SkipEngine.textMatches("跳过", r, texts, strong));
        check("精确 “跳过广告”", SkipEngine.textMatches("跳过广告", r, texts, strong));
        check("带空格 “跳 过”", SkipEngine.textMatches("跳 过", r, texts, strong));
        check("倒计时 “跳过5”", SkipEngine.textMatches("跳过5", r, texts, strong));
        check("倒计时 “跳过 5s”", SkipEngine.textMatches("跳过 5s", r, texts, strong));
        check("倒计时 “5s 跳过”", SkipEngine.textMatches("5s 跳过", r, texts, strong));
        check("竖线分隔 “跳过|5”", SkipEngine.textMatches("跳过|5", r, texts, strong));
        check("括号 “跳过(5)”", SkipEngine.textMatches("跳过(5)", r, texts, strong));
        check("英文 “Skip”", SkipEngine.textMatches("Skip", r, texts, strong));
        check("英文 “Skip Ad”", SkipEngine.textMatches("Skip Ad", r, texts, strong));
        check("大写 “SKIP”", SkipEngine.textMatches("SKIP", r, texts, strong));
        check("关闭广告", SkipEngine.textMatches("关闭广告", r, texts, strong));
        check("点击跳过", SkipEngine.textMatches("点击跳过", r, texts, strong));

        System.out.println("== 反向：不该跳过 ==");
        check("空文字", !SkipEngine.textMatches("", r, texts, strong));
        check("null", !SkipEngine.textMatches(null, r, texts, strong));
        check("黑名单 跳过此步骤", !SkipEngine.textMatches("跳过此步骤", r, texts, strong));
        check("黑名单 跳过此步骤详情", !SkipEngine.textMatches("跳过此步骤详情", r, texts, strong));
        check("黑名单 跳过引导", !SkipEngine.textMatches("跳过引导", r, texts, strong));
        check("黑名单 跳过教程", !SkipEngine.textMatches("跳过教程", r, texts, strong));
        check("黑名单 跳过登录", !SkipEngine.textMatches("跳过登录", r, texts, strong));
        check("黑名单 跳过验证", !SkipEngine.textMatches("跳过验证", r, texts, strong));
        check("黑名单 取消", !SkipEngine.textMatches("取消", r, texts, strong));
        check("超长句含跳过 不匹配",
                !SkipEngine.textMatches("点击跳过去看看更多精彩内容吧", r, texts, strong));
        check("普通文字 首页", !SkipEngine.textMatches("首页", r, texts, strong));
        check("普通文字 我的", !SkipEngine.textMatches("我的", r, texts, strong));
        check("普通文字 搜索", !SkipEngine.textMatches("搜索", r, texts, strong));
        check("普通文字 立即购买", !SkipEngine.textMatches("立即购买", r, texts, strong));
        check("普通文字 微信登录", !SkipEngine.textMatches("微信登录", r, texts, strong));
        check("普通文字 跳过学习", !SkipEngine.textMatches("跳过学习", r, texts, strong));

        System.out.println("== 常见 App 误伤回归 ==");
        String[] trap = {
                "同意并继续", "同意并接受", "一键登录", "本机号码一键登录", "新用户注册",
                "以后再说", "我知道了", "允许", "始终允许", "仅在使用中允许",
                "开启通知", "去评分", "查看详情", "领取优惠券", "立即领取",
                "刷新", "重试", "返回", "关闭", "确定", "下一步", "开始使用",
                "青少年模式", "用户协议", "隐私政策", "版本更新", "检查更新",
        };
        for (String t : trap) {
            if (SkipEngine.textMatches(t, r, texts, strong)) {
                fail++;
                System.out.println("  [FAIL] 误伤：" + t);
            } else {
                pass++;
            }
        }

        System.out.println("== 控件 ID 匹配 ==");
        List<String> ids = r.idsFor(null);
        check("后缀 skip_btn", SkipEngine.idMatches("com.x.y:id/skip_btn", ids));
        check("后缀 skip", SkipEngine.idMatches("com.x.y:id/skip", ids));
        check("后缀 iv_skip", SkipEngine.idMatches("com.x.y:id/iv_skip", ids));
        check("后缀 tv_skip", SkipEngine.idMatches("com.x.y:id/tv_skip", ids));
        check("后缀 splash_skip", SkipEngine.idMatches("com.x.y:id/splash_skip", ids));
        check("大小写 skipView", SkipEngine.idMatches("com.x.y:id/skipView", ids));
        check("close_ad", SkipEngine.idMatches("com.x.y:id/close_ad", ids));
        check("ad_close", SkipEngine.idMatches("com.x.y:id/ad_close", ids));
        check("无 id 不匹配", !SkipEngine.idMatches(null, ids));
        check("无关 id 不匹配", !SkipEngine.idMatches("com.x.y:id/title_bar", ids));
        check("无关 id 不匹配2", !SkipEngine.idMatches("com.x.y:id/btn_login", ids));
        check("后缀写法命中", SkipEngine.idMatches("com.x.y:id/whatever", Arrays.asList("whatever")));
        check("全限定写法命中",
                SkipEngine.idMatches("com.x.y:id/skip", Arrays.asList("com.x.y:id/skip")));
        check("全限定写法不误伤",
                !SkipEngine.idMatches("com.x.y:id/skip2", Arrays.asList("com.x.y:id/skip")));
        check("glob 前缀", SkipEngine.glob("skip*", "skip_btn"));
        check("glob 中缀", SkipEngine.glob("*skip*", "btn_skip_ad"));
        check("glob 完全", SkipEngine.glob("*", "anything"));
        check("glob 不匹配", !SkipEngine.glob("skip*", "btn_skip"));

        System.out.println("== 监听范围 ==");
        check("自己的包不监听", !r.shouldWatch("com.lbx.skipad"));
        check("桌面不监听", !r.shouldWatch("com.miui.home"));
        check("系统设置不监听", !r.shouldWatch("com.android.settings"));
        check("安装器不监听", !r.shouldWatch("com.android.packageinstaller"));
        check("普通应用监听(默认 watchAll)", r.shouldWatch("com.tencent.mm"));
        check("空包名不监听", !r.shouldWatch(""));
        check("null 不监听", !r.shouldWatch(null));

        System.out.println("== 逐应用覆写 ==");
        String withApp = "{\n"
                + "  \"global\": {\"watchAll\": false},\n"
                + "  \"skipTexts\": [\"跳过\"],\n"
                + "  \"strongTexts\": [\"跳过\"],\n"
                + "  \"apps\": {\n"
                + "    \"com.foo.bar\": {\"name\":\"福利吧\", \"enabled\":true,\n"
                + "                     \"extraTexts\":[\"关闭广告\"], \"extraIds\":[\"btn_close_ad\"]}\n"
                + "  }\n"
                + "}";
        RuleStore.Rules r2 = RuleStore.parse(withApp);
        check("watchAll=false 时未配置应用不监听", !r2.shouldWatch("com.tencent.mm"));
        check("watchAll=false 时已配置应用监听", r2.shouldWatch("com.foo.bar"));
        eq("应用显示名", r2.nameOf("com.foo.bar"), "福利吧");
        check("extraTexts 生效",
                SkipEngine.textMatches("关闭广告", r2, r2.textsFor("com.foo.bar"), r2.strongTexts));
        check("extraIds 生效",
                SkipEngine.idMatches("com.foo.bar:id/btn_close_ad", r2.idsFor("com.foo.bar")));
        check("其它应用拿不到 extraIds",
                !SkipEngine.idMatches("com.tencent.mm:id/btn_close_ad", r2.idsFor("com.tencent.mm")));
        check("textsFor(null) 不崩", r2.textsFor(null) != null);
        check("idsFor(null) 不崩", r2.idsFor(null) != null);
        check("nameOf(null) 不崩", r2.nameOf(null) == null);

        System.out.println("== 关闭的应用 ==");
        String disabled = "{\"global\":{\"watchAll\":true},"
                + "\"skipTexts\":[\"跳过\"],\"strongTexts\":[\"跳过\"],"
                + "\"apps\":{\"com.foo.bar\":{\"enabled\":false}}}";
        RuleStore.Rules r3 = RuleStore.parse(disabled);
        check("enabled=false 不监听", !r3.shouldWatch("com.foo.bar"));
        check("其它应用仍监听", r3.shouldWatch("com.tencent.mm"));

        System.out.println("== 容错 ==");
        boolean threw = false;
        try {
            RuleStore.parse("{ 这不是合法 json ");
        } catch (Throwable t) {
            threw = true;
        }
        check("非法 JSON 抛异常（由 validate 捕获）", threw);
        check("非法 JSON validate 返回错误信息",
                RuleStore.validate(null, "{ oops ") != null);
        check("缺规则 validate 返回错误信息",
                RuleStore.validate(null, "{\"skipTexts\":[],\"skipIds\":[]}") != null);
        check("合法规则 validate 通过",
                RuleStore.validate(null, raw) == null);

        System.out.println("== 边界 ==");
        RuleStore.Rules clamp = RuleStore.parse(
                "{\"global\":{\"scanIntervalMs\":1,\"maxTextLen\":99,\"retryLimit\":0,"
                        + "\"maxWaitMs\":99999999},\"skipTexts\":[\"跳过\"],\"strongTexts\":[\"跳过\"]}");
        eq("scanIntervalMs 下限 40", clamp.scanIntervalMs, 40);
        eq("maxTextLen 上限 20", clamp.maxTextLen, 20);
        eq("retryLimit 下限 1", clamp.retryLimit, 1);
        eq("maxWaitMs 上限 120000", clamp.maxWaitMs, 120000);

        // ================================================================
        // v2 新增：候选打分
        // ================================================================
        System.out.println("== v2 打分 ==");

        // 理想候选：精确文字 + 可点 + 小而靠右上 + 带倒计时
        SkipEngine.Cand good = cand(true, true, false, true, 0, 0.03, 0.88, 0.06, true);
        // 只有精确文字，其它都差
        SkipEngine.Cand plain = cand(false, true, false, false, -1, 0.40, 0.50, 0.50, false);
        // 大块容器：面积占比超标
        SkipEngine.Cand huge = cand(false, false, false, false, -1, 0.90, 0.50, 0.50, false);

        check("理想候选分数 >= 150", good.score() >= 150);
        check("理想候选分数 > 普通候选", good.score() > plain.score());
        check("普通候选分数 > 大容器", plain.score() > huge.score());
        check("大容器分数低于阈值", huge.score() < SkipEngine.MIN_SCORE);

        // 单项覆盖
        SkipEngine.Cand idOnly = cand(true, false, false, false, -1, 0.5, 0.5, 0.5, false);
        check("仅控件 ID 命中 -> 60 分", idOnly.score() == SkipEngine.S_ID);
        SkipEngine.Cand exactOnly = cand(false, true, false, false, -1, 0.5, 0.5, 0.5, false);
        check("仅精确文字 -> 80 分", exactOnly.score() == SkipEngine.S_EXACT);
        SkipEngine.Cand strongOnly = cand(false, false, true, false, -1, 0.5, 0.5, 0.5, false);
        check("仅强关键词 -> 40 分", strongOnly.score() == SkipEngine.S_STRONG);

        // 位置加成
        SkipEngine.Cand topRight = cand(false, true, false, false, -1, 0.5, 0.9, 0.05, false);
        SkipEngine.Cand center = cand(false, true, false, false, -1, 0.5, 0.5, 0.5, false);
        check("右上角 > 正中", topRight.score() > center.score());
        SkipEngine.Cand bottom = cand(false, true, false, false, -1, 0.5, 0.5, 0.9, false);
        check("底部有加成", bottom.score() > center.score());
        SkipEngine.Cand small = cand(false, true, false, false, -1, 0.04, 0.5, 0.5, false);
        check("小按钮 > 中等按钮", small.score() > center.score());

        // 可点击祖先深度
        SkipEngine.Cand anc1 = cand(false, true, false, false, 1, 0.5, 0.5, 0.5, false);
        SkipEngine.Cand anc2 = cand(false, true, false, false, 2, 0.5, 0.5, 0.5, false);
        SkipEngine.Cand ancNone = cand(false, true, false, false, -1, 0.5, 0.5, 0.5, false);
        check("深度1 > 深度2", anc1.score() > anc2.score());
        check("深度2 > 无祖先", anc2.score() > ancNone.score());

        // 倒计时加成
        SkipEngine.Cand digit = cand(false, true, false, false, -1, 0.5, 0.5, 0.5, true);
        check("带数字有加成", digit.score() > center.score());

        // pickBest
        List<SkipEngine.Cand> pool = new ArrayList<SkipEngine.Cand>();
        pool.add(plain);
        pool.add(huge);
        pool.add(good);
        check("pickBest 选中最优", SkipEngine.pickBest(pool) == good);

        List<SkipEngine.Cand> onlyBad = new ArrayList<SkipEngine.Cand>();
        onlyBad.add(huge);
        check("全部低于阈值 -> null", SkipEngine.pickBest(onlyBad) == null);
        check("空列表 -> null", SkipEngine.pickBest(new ArrayList<SkipEngine.Cand>()) == null);

        System.out.println("== v2 findBest 边界 ==");
        check("roots=null -> null",
                SkipEngine.findBest(null, null, r, "com.tencent.qqmusic") == null);
        check("roots 为空 -> null",
                SkipEngine.findBest(new ArrayList<AccessibilityNodeInfo>(),
                        new ArrayList<int[]>(), r, "com.tencent.qqmusic") == null);
        // 注意：collect 的节点预算必须只由 budget 控制。
        // 若再引入独立的 seen 计数器并与 budget 比较（seen++ 且 budget--），
        // 二者之和恒定，扫描会提前一半结束。这里用大量 null 根节点
        // 间接确认循环不会异常（null 会被 continue 跳过且不消耗栈）。
        List<AccessibilityNodeInfo> nulls = new ArrayList<AccessibilityNodeInfo>();
        for (int i = 0; i < 5; i++) nulls.add(null);
        check("roots 全为 null -> null",
                SkipEngine.findBest(nulls, null, r, "com.tencent.qqmusic") == null);

        System.out.println("== v2 matchKind ==");
        eq("精确匹配返回 2", SkipEngine.matchKind("跳过广告", r, texts, strong), 2);
        eq("包含匹配返回 1", SkipEngine.matchKind("跳过5", r, texts, strong), 1);
        eq("不匹配返回 0", SkipEngine.matchKind("首页", r, texts, strong), 0);
        eq("黑名单返回 0", SkipEngine.matchKind("跳过此步骤", r, texts, strong), 0);
        eq("空返回 0", SkipEngine.matchKind("", r, texts, strong), 0);

        System.out.println("== v2 hasDigit ==");
        check("跳过5 有数字", SkipEngine.hasDigit("跳过5"));
        check("5s 有数字", SkipEngine.hasDigit("5s"));
        check("跳过 无数字", !SkipEngine.hasDigit("跳过"));
        check("null 无数字", !SkipEngine.hasDigit(null));

        System.out.println("== v2 默认规则 ==");
        eq("retryLimit=8", r.retryLimit, 8);
        eq("scanIntervalMs=100", r.scanIntervalMs, 100);
        eq("blindTapEnabled 默认关", r.blindTapEnabled, false);
        eq("blindTapX 默认 0.86", r.blindTapX, 0.86);
        eq("blindTapY 默认 0.07", r.blindTapY, 0.07);
        // 注意：v2.0 时 strongTexts 含 "skip"/"skipad"，但 v3.0 起刻意移除
        // （这两个词过宽，会让靠 ID 命中的图标候选也进池，见下方 v3.0 小节）。
        check("QQ音乐有单独配置", r.apps.containsKey("com.tencent.qqmusic"));
        eq("QQ音乐显示名", r.nameOf("com.tencent.qqmusic"), "QQ音乐");
        check("跳过此广告 精确命中",
                SkipEngine.matchKind("跳过此广告", r, r.textsFor(null), r.strongTexts) == 2);
        check("关闭此广告 精确命中",
                SkipEngine.matchKind("关闭此广告", r, r.textsFor(null), r.strongTexts) == 2);
        check("跳过广告 > 精确命中",
                SkipEngine.matchKind("跳过广告 >", r, r.textsFor(null), r.strongTexts) == 2);

        System.out.println("== v2 盲点参数钳制 ==");
        RuleStore.Rules bt = RuleStore.parse(
                "{\"global\":{\"blindTapEnabled\":true,\"blindTapX\":5.0,\"blindTapY\":-3.0},"
                        + "\"skipTexts\":[\"跳过\"],\"strongTexts\":[\"跳过\"]}");
        eq("blindTapEnabled 生效", bt.blindTapEnabled, true);
        check("blindTapX 钳到 1.0", Math.abs(bt.blindTapX - 1.0) < 1e-9);
        check("blindTapY 钳到 0.0", Math.abs(bt.blindTapY - 0.0) < 1e-9);

        // ================================================================
        // 「有时能跳、有时不行」的两个根因（竞态）相关逻辑
        // ================================================================
        System.out.println("== 轮询间隔（不依赖事件也能扫） ==");
        eq("pollIntervalMs 默认 250", r.pollIntervalMs, 250);
        RuleStore.Rules pollClamp = RuleStore.parse(
                "{\"global\":{\"pollIntervalMs\":5},\"skipTexts\":[\"跳过\"],\"strongTexts\":[\"跳过\"]}");
        eq("pollIntervalMs 下限 80", pollClamp.pollIntervalMs, 80);
        RuleStore.Rules pollClamp2 = RuleStore.parse(
                "{\"global\":{\"pollIntervalMs\":99999},\"skipTexts\":[\"跳过\"],\"strongTexts\":[\"跳过\"]}");
        eq("pollIntervalMs 上限 2000", pollClamp2.pollIntervalMs, 2000);

        System.out.println("== 窗口排除（多窗口扫描的安全边界） ==");
        // 桌面窗口且当前监听的不是桌面 -> 跳过
        check("桌面窗口被跳过", r.isExcludedWindow("com.miui.home", "com.tencent.qqmusic"));
        check("系统设置窗口被跳过", r.isExcludedWindow("com.android.settings", "com.tencent.qqmusic"));
        check("安装器窗口被跳过", r.isExcludedWindow("com.android.packageinstaller", "com.tencent.qqmusic"));
        // 当前监听的应用本身即使在排除名单里也不跳过（否则永远扫不到）
        check("正在监听的应用不被跳过", !r.isExcludedWindow("com.tencent.qqmusic", "com.tencent.qqmusic"));
        // 广告 SDK 的独立窗口包名不在名单里 -> 正常扫描（这是能跳成功的关键）
        check("广告SDK窗口照常扫描", !r.isExcludedWindow("com.qq.e.ads", "com.tencent.qqmusic"));
        check("未知包名照常扫描", !r.isExcludedWindow("com.bytedance.sdk.openadsdk", "com.tencent.qqmusic"));
        // 拿不到包名时不冒险跳过（宁可扫描，否则广告在无名窗口里就漏了）
        check("包名为 null 不跳过", !r.isExcludedWindow(null, "com.tencent.qqmusic"));
        check("包名为空不跳过", !r.isExcludedWindow("", "com.tencent.qqmusic"));

        System.out.println("== v3.0 前台切换决策（decideForeground + FgContext） ==");
        String qq = "com.tencent.qqmusic";
        String dy = "com.ss.android.ugc.aweme";
        String adsdk = "com.bytedance.sdk.openadsdk";

        // 构造上下文的辅助：现在观察的是 watching，上一次前台是 lastFg，
        // 上一次观察的是 lastEp、结束于 endMs 前，期间是否见过系统窗口。
        // 用静态方法 ctx(...) 构造，见文件末尾。

        // ---- 观察中：绝不接管、绝不重启（修抖音误触 1） ----
        eq("同应用 -> FG_SAME", r.decideForeground(qq, ctx(qq, qq, null, 0, 1_000_000L, false, 1_000_000L)),
                RuleStore.Rules.FG_SAME);
        // 关键：广告 SDK 窗口到来时不再接管观察（v2.1 会 FG_SWITCH，正是误触根源）
        eq("观察中+广告SDK窗口 -> FG_KEEP",
                r.decideForeground(adsdk, ctx(qq, qq, null, 0, 1_000_000L, false, 1_000_000L)),
                RuleStore.Rules.FG_KEEP);
        eq("观察中+桌面 -> FG_KEEP",
                r.decideForeground("com.miui.home", ctx(qq, qq, null, 0, 1_000_000L, false, 1_000_000L)),
                RuleStore.Rules.FG_KEEP);
        eq("观察中+另一个应用 -> FG_KEEP（不换锚点）",
                r.decideForeground("com.tencent.mm", ctx(qq, qq, null, 0, 1_000_000L, false, 1_000_000L)),
                RuleStore.Rules.FG_KEEP);

        // ---- 同应用内换界面：没在观察时绝不能再开始（修抖音误触 2） ----
        // 这正是「打开全屏搜索会误触」的场景：lastFg 仍是抖音，只是界面变了。
        eq("没观察+同应用换界面 -> FG_IGNORE",
                r.decideForeground(dy, ctx(null, dy, dy, 0, 0L, false, 5_000_000L)),
                RuleStore.Rules.FG_IGNORE);

        // ---- 没在观察 + 真的切到了另一个需监听应用 -> 可以开始 ----
        eq("没观察+真切换应用 -> FG_START",
                r.decideForeground("com.tencent.mm", ctx(null, qq, qq, 0, 0L, false, 5_000_000L)),
                RuleStore.Rules.FG_START);
        eq("没观察+首次进入 -> FG_START",
                r.decideForeground(qq, ctx(null, null, null, 0, 0L, false, 5_000_000L)),
                RuleStore.Rules.FG_START);

        // ---- 冷却：刚从同一应用观察完、且期间没回过桌面 -> 忽略 ----
        // 场景：抖音开屏观察结束（超时），紧接着主界面窗口状态变化又来一次。
        eq("冷却中+同应用 -> FG_IGNORE",
                r.decideForeground(dy, ctx(null, qq, dy, 5_000L, 10_000L, false, 12_000L)),
                RuleStore.Rules.FG_IGNORE);
        // 但若期间真的回过桌面（见过系统窗口），说明是用户重新打开，允许再来一轮
        eq("冷却中但回过桌面 -> FG_START",
                r.decideForeground(dy, ctx(null, "com.miui.home", dy, 5_000L, 10_000L, true, 12_000L)),
                RuleStore.Rules.FG_START);
        // 冷却时间已过 -> 允许
        eq("冷却已过 + 同应用 -> FG_START",
                r.decideForeground(dy, ctx(null, qq, dy, 5_000L, 10_000L, false, 200_000L)),
                RuleStore.Rules.FG_START);

        // ---- 无需监听的包名 ----
        eq("没观察+桌面 -> FG_IGNORE",
                r.decideForeground("com.miui.home", ctx(null, qq, null, 0, 0L, false, 5_000_000L)),
                RuleStore.Rules.FG_IGNORE);
        eq("没观察+自身 -> FG_IGNORE",
                r.decideForeground("com.lbx.skipad", ctx(null, qq, null, 0, 0L, false, 5_000_000L)),
                RuleStore.Rules.FG_IGNORE);
        // ---- 空/null ----
        eq("空包名 -> FG_IGNORE",
                r.decideForeground("", ctx(qq, qq, null, 0, 0L, false, 5_000_000L)),
                RuleStore.Rules.FG_IGNORE);
        eq("null 包名 -> FG_IGNORE",
                r.decideForeground(null, ctx(qq, qq, null, 0, 0L, false, 5_000_000L)),
                RuleStore.Rules.FG_IGNORE);

        // ================================================================
        // v3.0 防误触：稳健模式的安全判定
        // ================================================================
        System.out.println("== v3.0 稳健模式安全闸门（isSafeCandidate） ==");
        RuleStore.Rules safe = RuleStore.parse(
                "{\"global\":{\"safeMode\":true,\"safeMaxArea\":0.12,"
                        + "\"safeTopBand\":0.30,\"safeBottomBand\":0.75},"
                        + "\"skipTexts\":[\"跳过\"],\"strongTexts\":[\"跳过\"]}");
        eq("safeMode 默认开", r.safeMode, true);
        eq("splashWindowMs 默认 6000", r.splashWindowMs, 6000);
        eq("episodeCooldownMs 默认 60000", r.episodeCooldownMs, 60000);
        eq("verifyBeforeTap 默认开", r.verifyBeforeTap, true);
        check("safeMaxArea 默认 0.12", Math.abs(r.safeMaxArea - 0.12) < 1e-9);

        // 正常的跳过按钮：小 + 右上角 -> 安全
        check("小按钮+右上角 -> 安全", safe.isSafeCandidate(0.03, 0.06));
        // 正常的跳过按钮：小 + 底部 -> 安全
        check("小按钮+底部 -> 安全", safe.isSafeCandidate(0.04, 0.92));
        // 顶部带边界内
        check("顶部带内(0.30) -> 安全", safe.isSafeCandidate(0.05, 0.30));
        check("底部带内(0.75) -> 安全", safe.isSafeCandidate(0.05, 0.75));
        // 屏幕正中 -> 不安全（正文/输入区，点错代价大）
        check("正中 -> 不安全", !safe.isSafeCandidate(0.04, 0.50));
        check("偏中上(0.45) -> 不安全", !safe.isSafeCandidate(0.04, 0.45));
        // 太大 -> 不安全
        check("大面积 -> 不安全", !safe.isSafeCandidate(0.30, 0.06));
        check("刚好超限 -> 不安全", !safe.isSafeCandidate(0.13, 0.06));
        // 面积未知(0)时只按位置判断，不因为拿不到尺寸就一律拒绝
        check("面积未知但位置安全 -> 安全", safe.isSafeCandidate(0.0, 0.06));
        // 关掉稳健模式 -> 一律放行
        RuleStore.Rules loose = RuleStore.parse(
                "{\"global\":{\"safeMode\":false},\"skipTexts\":[\"跳过\"],\"strongTexts\":[\"跳过\"]}");
        check("稳健模式关 -> 正中也可点", loose.isSafeCandidate(0.9, 0.50));

        System.out.println("== v3.0 安全参数钳制 ==");
        RuleStore.Rules sc = RuleStore.parse(
                "{\"global\":{\"splashWindowMs\":1,\"episodeCooldownMs\":1,"
                        + "\"safeMaxArea\":5.0,\"safeTopBand\":-1.0,\"safeBottomBand\":99.0},"
                        + "\"skipTexts\":[\"跳过\"],\"strongTexts\":[\"跳过\"]}");
        eq("splashWindowMs 下限 1000", sc.splashWindowMs, 1000);
        eq("episodeCooldownMs 下限 1000", sc.episodeCooldownMs, 1000);
        check("safeMaxArea 上限 1.0", Math.abs(sc.safeMaxArea - 1.0) < 1e-9);
        check("safeTopBand 下限 0.05", Math.abs(sc.safeTopBand - 0.05) < 1e-9);
        check("safeBottomBand 上限 0.99", Math.abs(sc.safeBottomBand - 0.99) < 1e-9);

        System.out.println("== v3.0 strongTexts 收窄（防图标误伤） ==");
        // v3.0 去掉了 "skipad"/"skip" 这两个过宽的词，
        // 否则靠 ID 命中的纯图标候选也能进候选池。
        check("strongTexts 不含过宽的 skip", !r.strongTexts.contains("skip"));
        check("strongTexts 不含过宽的 skipad", !r.strongTexts.contains("skipad"));
        check("strongTexts 仍含 跳过", r.strongTexts.contains("跳过"));

        System.out.println("== v3.0 抖音单独规则 ==");
        check("抖音有单独配置", r.apps.containsKey(dy));
        eq("抖音显示名", r.nameOf(dy), "抖音");
        check("抖音可监听", r.shouldWatch(dy));

        System.out.println("== v3.0 pickBest 优先安全候选 ==");
        // 高分但不安全（例如正中大块）vs 低分但安全的小按钮
        SkipEngine.Cand riskyHigh = cand(true, true, false, true, 0, 0.40, 0.95, 0.50, false);
        riskyHigh.safe = false;                      // 正中 -> 不安全
        SkipEngine.Cand safeLow = cand(false, true, false, false, -1, 0.03, 0.90, 0.06, false);
        safeLow.safe = true;                         // 右上小按钮 -> 安全

        check("不安全候选分更高（前提成立）", riskyHigh.score() > safeLow.score());
        List<SkipEngine.Cand> mixed = new ArrayList<SkipEngine.Cand>();
        mixed.add(riskyHigh);
        mixed.add(safeLow);
        check("优先返回安全候选", SkipEngine.pickBest(mixed) == safeLow);

        // 只有不安全候选时，仍返回它（由 Service 记日志并拦下，保持诊断可见）
        List<SkipEngine.Cand> onlyRisky = new ArrayList<SkipEngine.Cand>();
        onlyRisky.add(riskyHigh);
        check("只剩不安全候选 -> 仍返回（供服务层拦下并记录）",
                SkipEngine.pickBest(onlyRisky) == riskyHigh);

        // 都安全时按分数取高
        SkipEngine.Cand s1 = cand(true, true, false, true, 0, 0.03, 0.90, 0.06, true);
        s1.safe = true;
        SkipEngine.Cand s2 = cand(false, true, false, false, -1, 0.05, 0.88, 0.10, false);
        s2.safe = true;
        List<SkipEngine.Cand> twoSafe = new ArrayList<SkipEngine.Cand>();
        twoSafe.add(s1);
        twoSafe.add(s2);
        check("都安全时取分高者", SkipEngine.pickBest(twoSafe) == s1);

        // ================================================================
        // v3.1 摇一摇广告放宽
        // ================================================================
        System.out.println("== v3.1 摇一摇广告特征识别（isAdSignal） ==");
        check("adTexts 非空", !r.adTexts.isEmpty());
        check("shakeAware 默认开", r.shakeAware);
        check("shakeBlindTap 默认关", !r.shakeBlindTap);
        check("adTexts 含 摇一摇", r.adTexts.contains("摇一摇"));
        check("adTexts 含 摇动手机", r.adTexts.contains("摇动手机"));

        check("命中「摇一摇」", r.isAdSignal("摇一摇"));
        check("命中「摇一摇跳转详情」", r.isAdSignal("摇一摇跳转详情"));
        check("命中「摇动手机」", r.isAdSignal("摇动手机"));
        check("命中带标点「摇一摇，了解更多」", r.isAdSignal("摇一摇，了解更多"));
        check("不命中「跳过」", !r.isAdSignal("跳过"));
        check("不命中「设置」", !r.isAdSignal("设置"));
        check("不命中空串", !r.isAdSignal(""));
        check("不命中 null", !r.isAdSignal(null));

        System.out.println("== v3.1 广告页放宽安全带（isSafeCandidate 三参） ==");
        RuleStore.Rules shake = RuleStore.parse(
                "{\"global\":{\"safeMode\":true,\"safeMaxArea\":0.12,\"safeTopBand\":0.30,"
                        + "\"safeBottomBand\":0.75,\"shakeAware\":true,\"adTopBand\":0.60,"
                        + "\"adBottomBand\":0.75,\"adMaxArea\":0.25},"
                        + "\"skipTexts\":[\"跳过\"],\"strongTexts\":[\"跳过\"]}");

        // v3.0 会拦下的位置：纵位置 0.45，非广告页仍然拦下（回归保护）
        check("非广告页 0.45 -> 不安全（v3.0 行为不变）",
                !shake.isSafeCandidate(0.04, 0.45, false));
        // 同一位置，但已确认是摇一摇广告页 -> 放宽后安全
        check("广告页 0.45 -> 安全（v3.1 放宽）", shake.isSafeCandidate(0.04, 0.45, true));
        check("广告页 0.55 -> 安全", shake.isSafeCandidate(0.04, 0.55, true));
        check("广告页 0.60（顶部带边界）-> 安全", shake.isSafeCandidate(0.04, 0.60, true));
        // 中间走廊永远不点：顶部带 0.60 与底部带 0.75 之间
        check("广告页 0.70 走廊内 -> 不安全", !shake.isSafeCandidate(0.04, 0.70, true));
        check("广告页 0.75（底部带边界）-> 安全", shake.isSafeCandidate(0.04, 0.75, true));
        check("广告页 0.85 -> 安全（底部带）", shake.isSafeCandidate(0.04, 0.85, true));
        // 面积：普通模式下 0.20 超限，广告页放宽到 0.25 后放行
        check("非广告页 面积0.20 -> 不安全", !shake.isSafeCandidate(0.20, 0.45, false));
        check("广告页 面积0.20 -> 安全", shake.isSafeCandidate(0.20, 0.45, true));
        check("广告页 面积0.30 -> 不安全（超 adMaxArea）",
                !shake.isSafeCandidate(0.30, 0.45, true));
        // 关掉 shakeAware -> 回到 v3.0 行为
        RuleStore.Rules noShake = RuleStore.parse(
                "{\"global\":{\"safeMode\":true,\"shakeAware\":false},"
                        + "\"skipTexts\":[\"跳过\"],\"strongTexts\":[\"跳过\"]}");
        check("shakeAware 关 -> 广告页也不放宽",
                !noShake.isSafeCandidate(0.04, 0.45, true));
        // 二参重载等价于 adSignal=false
        check("二参重载 == adSignal false",
                shake.isSafeCandidate(0.04, 0.45) == shake.isSafeCandidate(0.04, 0.45, false));

        System.out.println("== v3.1 广告页安全带钳制 ==");
        RuleStore.Rules adClamp = RuleStore.parse(
                "{\"global\":{\"adTopBand\":5.0,\"adBottomBand\":-5.0,\"adMaxArea\":9.0},"
                        + "\"skipTexts\":[\"跳过\"],\"strongTexts\":[\"跳过\"]}");
        check("adTopBand 上限 0.80", Math.abs(adClamp.adTopBand - 0.80) < 1e-9);
        // 上下带之间必须留走廊：adBottomBand 被抬到 adTopBand+0.05 以上
        check("上下带之间留走廊",
                adClamp.adBottomBand >= adClamp.adTopBand + 0.05 - 1e-9
                        || adClamp.adBottomBand >= 0.99 - 1e-9);
        check("adMaxArea 上限 0.55", Math.abs(adClamp.adMaxArea - 0.55) < 1e-9);

        System.out.println("== v3.1 摇一摇广告下免除文字依据 ==");
        // 摇一摇广告的「跳过」常是纯图片（无文字）。已确认广告页时应放行，
        // 非广告页时仍要求文字依据（保护抖音搜索图标场景）。
        check("adTexts 不含过宽的「下载」", !r.adTexts.contains("下载"));
        check("adTexts 不含过宽的「立即」", !r.adTexts.contains("立即"));

        // 默认规则必须真的能救回被 logo 挤下来的「跳过」按钮。
        // 否则参数写得再漂亮也只是纸面放宽。
        check("默认 adTopBand = 0.60", Math.abs(r.adTopBand - 0.60) < 1e-9);
        check("默认 adBottomBand = 0.75（与普通页一致，不动底部带）",
                Math.abs(r.adBottomBand - 0.75) < 1e-9);
        check("默认 adMaxArea = 0.25", Math.abs(r.adMaxArea - 0.25) < 1e-9);
        check("默认规则下：广告页 0.45 可点（v3.1 要救的正是一带）",
                r.isSafeCandidate(0.04, 0.45, true));
        check("默认规则下：非广告页 0.45 仍不可点（防线没被拆）",
                !r.isSafeCandidate(0.04, 0.45, false));
        check("默认规则下：广告页 0.45 处放宽后仍不放过超大候选",
                !r.isSafeCandidate(0.40, 0.45, true));

        // ================================================================
        // v3.2 身份已确认按钮：免面积上限 / 免位置安全带 / 免文字闸门
        // 专门救「带倒计时的大『跳过』条」「SDK 关闭按钮」「互动广告关闭键」。
        // ================================================================
        System.out.println("== v3.2 身份已确认：大按钮也能点 ==");
        // 普通模式下，中部 0.50、面积 0.40 的大块原本会被 safeMaxArea + 安全带双重拦下。
        check("普通模式 中部大块(0.40) -> 不安全（v3.0 行为不变）",
                !r.isSafeCandidate(0.40, 0.50, false, false));
        // 同一大块，但身份已确认（精确 skip 文字 / 命中 skip id）-> 放行
        // （仍受 CONTAINER_AREA_CAP=0.55 约束，全屏容器不算）。
        check("身份已确认 中部大块(0.40) -> 安全（v3.2 救大『跳过』条）",
                r.isSafeCandidate(0.40, 0.50, false, true));
        // 全屏容器（>0.55）即使身份已确认也不放行 —— 那是广告大图/视频，点中会跳转。
        check("身份已确认 全屏容器(0.70) -> 仍不安全（防点进落地页）",
                !r.isSafeCandidate(0.70, 0.50, false, true));

        System.out.println("== v3.2 身份已确认：免文字闸门（救 SDK 关闭键/互动广告） ==");
        // 仅靠 ID 命中的纯图标关闭按钮（无文字），普通模式会被「必须有文字依据」拦下；
        // v3.2 中 idHit 即视为身份已确认，绕过该闸门。
        // （collect() 里 c.safe 的闸门为 !safeMode || textHit || idHit || relaxed，
        //   这里用 isSafeCandidate 的 identified 分支验证位置/面积逻辑，文字闸门在 collect 层覆盖。）
        check("身份已确认 仅靠 ID 的大关闭按钮(0.35) -> 安全",
                r.isSafeCandidate(0.30, 0.35, false, true));
        check("身份已确认 仅靠 ID 的关闭按钮落底部 -> 安全",
                r.isSafeCandidate(0.05, 0.92, false, true));

        System.out.println("== v3.2 关闭键文案/ID 扩充（救互动广告） ==");
        check("skipTexts 含 关闭广告", r.skipTexts.contains("关闭广告"));
        check("skipTexts 含 关闭该广告", r.skipTexts.contains("关闭该广告"));
        check("skipTexts 含 点击关闭", r.skipTexts.contains("点击关闭"));
        check("skipIds 含 开屏关闭 *:splash_close*", r.skipIds.contains("*:*splash_close*"));


        System.out.println();
        System.out.println("========================");
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("========================");
        if (fail > 0) System.exit(1);
    }

    /** 构造 decideForeground 的上下文（参数顺序：watching/lastFg/lastEp/endMs/sawSys/now）。 */
    private static RuleStore.Rules.FgContext ctx(String watching, String lastFg, String lastEp,
                                                long endMs, long lastEndMs, boolean sawSys, long now) {
        RuleStore.Rules.FgContext c = new RuleStore.Rules.FgContext();
        c.watchingPkg = watching;
        c.lastFgPkg = lastFg;
        c.lastEpisodePkg = lastEp;
        c.lastEpisodeEndMs = lastEndMs;
        c.sawSystemSinceEpisode = sawSys;
        c.now = now;
        return c;
    }

    /**
     * 造一个候选。字段都是纯数据，不碰 AccessibilityNodeInfo，
     * 所以可以在桌面 JVM 上直接测打分逻辑。
     */
    private static SkipEngine.Cand cand(boolean idHit, boolean exactHit, boolean strongHit,
                                        boolean clickable, int clickableDepth, double areaRatio,
                                        double nx, double ny, boolean hasDigit) {
        SkipEngine.Cand c = new SkipEngine.Cand();
        c.idHit = idHit;
        c.exactHit = exactHit;
        c.strongHit = strongHit;
        c.clickable = clickable;
        c.clickableDepth = clickableDepth;
        c.areaRatio = areaRatio;
        c.nx = nx;
        c.ny = ny;
        c.hasDigit = hasDigit;
        // 身份已确认：精确文字或命中 id（与 collect() 的判定一致），便于 v3.2 测试复用。
        c.identified = exactHit || idHit;
        // 默认按「小而靠边」判安全，与 collect() 的算法一致；
        // 需要构造不安全候选时由调用方覆盖。
        c.safe = areaRatio <= 0.12 && (ny <= 0.30 || ny >= 0.75);
        c.textHit = exactHit || strongHit;
        return c;
    }

    private static String readResource(String path) throws Exception {
        java.io.InputStream in = EngineTest.class.getResourceAsStream(path);
        if (in == null) throw new IllegalStateException("找不到资源 " + path);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }
}
