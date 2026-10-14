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
        check("strongTexts 含 skip", r.strongTexts.contains("skip"));
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

        System.out.println("== 前台切换决策（decideForeground，五种情况） ==");
        String qq = "com.tencent.qqmusic";
        // 正在监听 QQ音乐
        eq("同应用 -> FG_SAME", r.decideForeground(qq, qq), RuleStore.Rules.FG_SAME);
        eq("切到桌面 -> FG_KEEP", r.decideForeground("com.miui.home", qq), RuleStore.Rules.FG_KEEP);
        eq("切到系统设置 -> FG_KEEP", r.decideForeground("com.android.settings", qq), RuleStore.Rules.FG_KEEP);
        eq("切到系统UI -> FG_KEEP", r.decideForeground("com.android.systemui", qq), RuleStore.Rules.FG_KEEP);
        // 广告 SDK 的包名不在排除名单里，watchAll=true 时它算「可监听」，
        // 因此走 FG_SWITCH（重开一个观察窗口）。这是可接受的：
        // 扫描本来就会遍历所有未排除窗口，QQ音乐那扇窗照样会被扫到，
        // 而重开窗口只是把截止时间往后推，反而更容易等到广告渲染出来。
        eq("广告SDK窗口 -> FG_SWITCH（无害）",
                r.decideForeground("com.qq.e.ads", qq), RuleStore.Rules.FG_SWITCH);
        eq("切到微信 -> FG_SWITCH", r.decideForeground("com.tencent.mm", qq), RuleStore.Rules.FG_SWITCH);
        // 没在观察时（这是上一版写出过的回归：watchPkg=null 时必须能开始监听）
        eq("没在观察+需监听应用 -> FG_START", r.decideForeground(qq, null), RuleStore.Rules.FG_START);
        eq("没在观察+微信 -> FG_START", r.decideForeground("com.tencent.mm", null), RuleStore.Rules.FG_START);
        eq("没在观察+桌面 -> FG_IGNORE", r.decideForeground("com.miui.home", null), RuleStore.Rules.FG_IGNORE);
        eq("没在观察+自身 -> FG_IGNORE", r.decideForeground("com.lbx.skipad", null), RuleStore.Rules.FG_IGNORE);
        eq("空包名 -> FG_IGNORE", r.decideForeground("", qq), RuleStore.Rules.FG_IGNORE);
        eq("null 包名 -> FG_IGNORE", r.decideForeground(null, qq), RuleStore.Rules.FG_IGNORE);

        System.out.println();
        System.out.println("========================");
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("========================");
        if (fail > 0) System.exit(1);
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
