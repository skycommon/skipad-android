package com.lbx.skipad;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 主界面：状态展示 + 开关 + 规则编辑。
 * 界面全部用代码构建，不依赖 AndroidX，保证在纯 SDK 环境下零额外依赖编译。
 */
public class MainActivity extends android.app.Activity {

    private static final int C_BG = 0xFFF2F4F8;
    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_TEXT = 0xFF1B1F27;
    private static final int C_SUB = 0xFF7A8494;
    private static final int C_PRIMARY = 0xFF2F6BFF;
    private static final int C_OK = 0xFF12B76A;
    private static final int C_WARN = 0xFFF79009;

    private LinearLayout root;
    private TextView statusPill;
    private TextView statusDetail;
    private TextView ruleSummary;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    // ------------------------------------------------------------------ 界面构建

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        sv.setFillViewport(true);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, dp(32));
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildHeader();
        buildStatusCard();
        buildSwitchesCard();
        buildActionsCard();
        buildHelpCard();

        setContentView(sv);
        refreshStatus();
    }

    private void buildHeader() {
        TextView t = new TextView(this);
        t.setText("跳过广告");
        t.setTextSize(26);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(C_TEXT);
        root.addView(t);

        TextView s = new TextView(this);
        s.setText("v3.4 · 开机即生效 · 卡住自救 · 12 秒观察窗口 · 防误触 · 摇一摇广告 · 多窗口扫描");
        s.setTextSize(13);
        s.setTextColor(C_SUB);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(18);
        s.setLayoutParams(lp);
        root.addView(s);
    }

    private void buildStatusCard() {
        LinearLayout card = card();

        statusPill = new TextView(this);
        statusPill.setTextSize(17);
        statusPill.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(statusPill);

        statusDetail = new TextView(this);
        statusDetail.setTextSize(13);
        statusDetail.setTextColor(C_SUB);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(14);
        statusDetail.setLayoutParams(lp);
        card.addView(statusDetail);

        Button open = primaryButton("打开无障碍设置");
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        });
        card.addView(open);

        // 很多手机的无障碍列表把第三方服务折叠在“已下载的服务”里，
        // 或者干脆只认系统白名单。这个按钮直连本服务的开关页。
        Button direct = outlineButton("找不到「跳过广告」？点这里直达开关");
        direct.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openServiceDetail();
            }
        });
        card.addView(direct);

        // Android 13 起，侧载安装的应用默认带“受限设置”：
        // 无障碍开关会变灰并提示“受限制的设置”，必须去应用信息页手动放行。
        if (Build.VERSION.SDK_INT >= 33) {
            Button unlock = outlineButton("开关是灰的？点这里解除受限设置");
            unlock.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openAppDetails();
                }
            });
            card.addView(unlock);
        }

        root.addView(card);
    }

    private void buildSwitchesCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("功能开关"));

        card.addView(switchRow("启用跳过功能", "总开关，关闭后完全不工作",
                RuleStore.isMasterEnabled(this), new OnChecked() {
                    @Override
                    public void onChecked(boolean v) {
                        RuleStore.setMasterEnabled(MainActivity.this, v);
                    }
                }));

        card.addView(divider());

        card.addView(switchRow("跳过时震动", null,
                RuleStore.isVibrate(this), new OnChecked() {
                    @Override
                    public void onChecked(boolean v) {
                        RuleStore.setVibrate(MainActivity.this, v);
                    }
                }));

        card.addView(divider());

        card.addView(switchRow("跳过时提示", "在屏幕下方显示一行提示",
                RuleStore.isToast(this), new OnChecked() {
                    @Override
                    public void onChecked(boolean v) {
                        RuleStore.setToast(MainActivity.this, v);
                    }
                }));

        card.addView(divider());

        card.addView(switchRow("监听所有应用", "关闭后只监听规则里单独配置的应用",
                isWatchAll(), new OnChecked() {
                    @Override
                    public void onChecked(boolean v) {
                        setGlobalBool("watchAll", v);
                        refreshStatus();
                    }
                }));

        card.addView(divider());

        // 稳健模式：v3.0 起默认开启，是防误触的主力开关。
        card.addView(switchRow("稳健模式（推荐开启）",
                "只在小按钮、且位于屏幕顶部/底部、且有文字依据时才点；宁可不跳也不误触",
                isSafeMode(), new OnChecked() {
                    @Override
                    public void onChecked(boolean v) {
                        setGlobalBool("safeMode", v);
                        refreshStatus();
                    }
                }));

        card.addView(divider());

        card.addView(switchRow("抓取诊断快照", "跳不掉时记下当前界面文字，便于排查",
                RuleStore.isDiagnose(this), new OnChecked() {
                    @Override
                    public void onChecked(boolean v) {
                        RuleStore.setDiagnose(MainActivity.this, v);
                    }
                }));

        card.addView(divider());

        card.addView(switchRow("点击前复核位置",
                "防止广告消失后，点击落到同一位置新出现的图标上（如抖音的搜索）",
                isVerifyBeforeTap(), new OnChecked() {
                    @Override
                    public void onChecked(boolean v) {
                        setGlobalBool("verifyBeforeTap", v);
                        refreshStatus();
                    }
                }));

        card.addView(divider());

        card.addView(switchRow("盲点兜底", "完全找不到按钮时，盲点右上角一次。可能误点，谨慎开启",
                isBlindTap(), new OnChecked() {
                    @Override
                    public void onChecked(boolean v) {
                        setGlobalBool("blindTapEnabled", v);
                        refreshStatus();
                    }
                }));

        card.addView(divider());

        // v3.4：卡住自救。专治「卡在开屏广告上，进不去也退不出」。
        // 为什么值得做成默认开：广告 SDK 把开屏页的返回键重写成了「结束广告」，
        // 所以按返回对用户等价于跳过；而万一不等价，也至少让人能退出去。
        // 安全性来自前置条件 —— 只有先确认这一屏确实是广告页（有「互动广告」
        // 这类措辞）才会动手，普通界面绝不会按返回。
        card.addView(switchRow("卡住自救（按返回键）",
                "开屏广告怎么都跳不掉时，等一会儿自动按一次返回键。仅在确认是广告页时生效",
                isBackKeyFallback(), new OnChecked() {
                    @Override
                    public void onChecked(boolean v) {
                        setGlobalBool("backKeyFallback", v);
                        refreshStatus();
                    }
                }));

        root.addView(card);
    }

    private void buildActionsCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("规则"));

        ruleSummary = new TextView(this);
        ruleSummary.setTextSize(13);
        ruleSummary.setTextColor(C_SUB);
        ruleSummary.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        ruleSummary.setLayoutParams(lp);
        card.addView(ruleSummary);

        LinearLayout row1 = buttonRow();
        row1.addView(secondaryButton("编辑规则", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editRules();
            }
        }));
        row1.addView(secondaryButton("查看日志", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLogs();
            }
        }));
        card.addView(row1);

        LinearLayout row2 = buttonRow();
        row2.addView(secondaryButton("查看诊断", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDiagnostics();
            }
        }));
        row2.addView(secondaryButton("恢复默认规则", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReset();
            }
        }));
        card.addView(row2);

        LinearLayout row3 = buttonRow();
        row3.addView(secondaryButton("清空日志", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RuleStore.prefs(MainActivity.this).edit()
                        .putString(RuleStore.K_LOG, "[]").apply();
                toast("日志已清空");
            }
        }));
        row3.addView(secondaryButton("清空诊断", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RuleStore.prefs(MainActivity.this).edit()
                        .putString(RuleStore.K_DIAG, "[]").apply();
                toast("诊断记录已清空");
            }
        }));
        card.addView(row3);

        root.addView(card);
    }

    private void buildHelpCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("使用说明"));

        TextView t = new TextView(this);
        t.setTextSize(13);
        t.setTextColor(C_SUB);
        t.setLineSpacing(dp(5), 1f);
        t.setText(
                "1. 用数据线或传输工具把 APK 装到手机（需允许“安装未知应用”）。\n\n"
                        + "2. 打开本应用，点“打开无障碍设置”，在其中找到“跳过广告”并开启。\n\n"
                        + "3. 部分手机（小米/华为/OPPO/vivo）还需要在“电池优化”或“后台管理”里"
                        + "把本应用设为不受限制，并在“自启动”里允许它，服务开机后才不会被系统杀掉。\n\n"
                        + "4. 之后正常打开其他应用，开屏广告出现时会自动点掉。\n\n"
                        + "v3.4 说明：不再只等系统通知。服务会自己每隔不到一秒看一眼当前是哪个应用，"
                        + "所以手机刚开机、系统没发出任何通知的那段时间也照样能跳。"
                        + "开屏广告的观察窗口放宽到 12 秒，足够覆盖“启动页 → 广告盖上来”的全过程。\n\n"
                        + "卡住自救：如果确认当前是广告页（有“互动广告 / 摇一摇”这类字样），"
                        + "却一直找不到“跳过”按钮，等 3.5 秒会自动按一次返回键结束广告 —— "
                        + "广告页的返回等价于跳过，也让你不至于卡在开屏界面既进不去也退不出。"
                        + "普通界面绝不会触发。\n\n"
                        + "原理与安全：本应用只在你打开某个应用后的最初几秒内读取当前界面，"
                        + "寻找写着“跳过 / 跳过广告 / Skip”之类的按钮并点击；"
                        + "超时或进入正常界面后立即停止，不会读取输入框，也不联网。");
        card.addView(t);

        root.addView(card);
    }

    // ------------------------------------------------------------------ 状态刷新

    private void refreshStatus() {
        if (statusPill == null) return;

        if (isAccessibilityEnabled()) {
            statusPill.setText("● 已启用");
            statusPill.setTextColor(C_OK);
            statusDetail.setText("无障碍服务正在运行 · " + SkipAccessibilityService.statusText());
        } else {
            statusPill.setText("● 未启用");
            statusPill.setTextColor(C_WARN);
            statusDetail.setText("需要开启无障碍服务才能自动跳过广告");
        }
        refreshRuleSummary();
    }

    private void refreshRuleSummary() {
        if (ruleSummary == null) return;
        try {
            RuleStore.Rules r = RuleStore.load(this);
            StringBuilder sb = new StringBuilder();
            sb.append("跳过文字：").append(r.skipTexts.size()).append(" 条\n");
            sb.append("关键词：").append(join(r.strongTexts)).append('\n');
            sb.append("控件 ID：").append(r.skipIds.size()).append(" 条\n");
            sb.append("排除应用：").append(r.excludeApps.size()).append(" 个\n");
            sb.append("单独配置：").append(r.apps.size()).append(" 个\n");
            sb.append("摇一摇广告放宽：").append(r.shakeAware ? "开" : "关")
              .append("（特征词 ").append(r.adTexts.size()).append(" 条）\n");
            sb.append("观察窗口：").append(r.splashWindowMs / 1000).append(" 秒 · 扫描间隔 ")
                    .append(r.scanIntervalMs).append(" 毫秒\n");
            sb.append("前台轮询：").append(r.fgPollMs).append(" 毫秒")
              .append("（观察期自动减半）· 冷却 ").append(r.episodeCooldownMs / 1000).append(" 秒\n");
            sb.append("卡住自救：").append(r.backKeyFallback ? "开" : "关")
              .append("（确认广告页后 ").append(r.backKeyDelayMs).append(" 毫秒按返回）");
            ruleSummary.setText(sb.toString());
        } catch (Throwable t) {
            ruleSummary.setText("规则读取失败：" + t.getMessage());
        }
    }

    private static String join(List<String> l) {
        if (l == null || l.isEmpty()) return "—";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append('、');
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    private boolean isAccessibilityEnabled() {
        try {
            ComponentName me = new ComponentName(this, SkipAccessibilityService.class);
            String flat = me.flattenToString();
            String s = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(s)) return false;
            String lower = s.toLowerCase();
            return lower.contains(flat.toLowerCase())
                    || lower.contains(me.flattenToShortString().toLowerCase());
        } catch (Throwable t) {
            return SkipAccessibilityService.isRunning();
        }
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            toast("请在列表底部「已下载的服务」中找「跳过广告」");
        } catch (Throwable t) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    /**
     * 直接跳到「跳过广告」这个无障碍服务自己的开关页。
     * 比让用户在大列表里翻找可靠得多。需要 API 26+。
     *
     * 注意：ACTION_ACCESSIBILITY_DETAILS_SETTINGS 是系统隐藏常量，
     * 不在公开 SDK 里（android.jar 中没有），所以这里用字面量。
     */
    private static final String ACTION_ACCESSIBILITY_DETAILS =
            "android.settings.ACCESSIBILITY_DETAILS_SETTINGS";

    private void openServiceDetail() {
        ComponentName me = new ComponentName(this, SkipAccessibilityService.class);
        Intent i = new Intent(ACTION_ACCESSIBILITY_DETAILS);
        i.putExtra("extra_component_name", me.flattenToString());
        try {
            startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        // 退一步：不带组件名，系统会展示本应用的无障碍详情页
        try {
            Intent i2 = new Intent(ACTION_ACCESSIBILITY_DETAILS);
            i2.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i2);
            return;
        } catch (Throwable ignored) {
        }
        // 再退一步：回到无障碍总列表
        openAccessibilitySettings();
    }

    /**
     * 跳到「应用信息」页。Android 13 及以上，侧载安装的应用默认带“受限设置”，
     * 无障碍开关会变灰且提示“受限制的设置”，必须在这里点右上角菜单允许。
     */
    private void openAppDetails() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            toast("请手动进入 设置 → 应用 → 跳过广告");
        }
    }

    // ------------------------------------------------------------------ 规则编辑

    private void editRules() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setTypeface(Typeface.MONOSPACE);
        et.setTextSize(12);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setText(RuleStore.getRawJson(this));
        et.setHorizontallyScrolling(false);

        ScrollView sc = new ScrollView(this);
        int pad = dp(12);
        sc.setPadding(pad, pad, pad, pad);
        sc.addView(et, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));

        new AlertDialog.Builder(this)
                .setTitle("编辑规则（JSON）")
                .setView(sc)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String json = et.getText().toString();
                        String err = RuleStore.validate(MainActivity.this, json);
                        if (err != null) {
                            toast(err);
                            return;
                        }
                        RuleStore.setRawJson(MainActivity.this, json);
                        toast("规则已保存");
                        refreshStatus();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("恢复默认规则")
                .setMessage("将丢弃你对规则的所有修改，确定继续吗？")
                .setPositiveButton("恢复", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        RuleStore.resetToDefault(MainActivity.this);
                        toast("已恢复默认规则");
                        refreshStatus();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showLogs() {
        String raw = RuleStore.prefs(this).getString(RuleStore.K_LOG, null);
        StringBuilder sb = new StringBuilder();
        try {
            if (!TextUtils.isEmpty(raw)) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    sb.append(o.optString("t")).append("  ")
                            .append(o.optString("app")).append('\n')
                            .append("    ").append(o.optString("why")).append("\n\n");
                }
            }
        } catch (Throwable ignored) {
        }
        if (sb.length() == 0) sb.append("暂无记录。\n\n成功跳过广告后会记录在这里。");

        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(13);
        tv.setTextColor(C_TEXT);
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));

        ScrollView sc = new ScrollView(this);
        sc.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle("跳过记录")
                .setView(sc)
                .setPositiveButton("关闭", null)
                .show();
    }

    /**
     * 显示诊断快照：某个 App 跳不掉时，这里能看到它开屏界面的
     * 全部文字与可点击控件，照着把真正的按钮文字加进规则即可。
     */
    private void showDiagnostics() {
        String raw = RuleStore.prefs(this).getString(RuleStore.K_DIAG, null);
        StringBuilder sb = new StringBuilder();
        try {
            if (!TextUtils.isEmpty(raw)) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i, null);
                    if (s == null || s.isEmpty()) continue;
                    sb.append("========== 快照 ").append(i + 1).append(" ==========\n");
                    sb.append(s).append('\n');
                }
            }
        } catch (Throwable ignored) {
        }
        if (sb.length() == 0) {
            sb.append("暂无诊断记录。\n\n")
              .append("用法：保持「抓取诊断快照」开启，然后正常打开某个跳不掉广告的 App，")
              .append("等广告自己结束后，回来点这个按钮。\n\n")
              .append("这里会列出该界面上所有文字和可点击控件，")
              .append("你可以照着把真正的「跳过」按钮文字填进「编辑规则」的 skipTexts 里。");
        }

        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(12);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(C_TEXT);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));

        ScrollView sc = new ScrollView(this);
        sc.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle("诊断快照（可长按复制）")
                .setView(sc)
                .setPositiveButton("关闭", null)
                .show();
    }

    // ------------------------------------------------------------------ 规则内联写

    private boolean isWatchAll() {
        try {
            JSONObject root = new JSONObject(RuleStore.getRawJson(this));
            JSONObject g = root.optJSONObject("global");
            return g == null || g.optBoolean("watchAll", true);
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean isBlindTap() {
        try {
            JSONObject root = new JSONObject(RuleStore.getRawJson(this));
            JSONObject g = root.optJSONObject("global");
            return g != null && g.optBoolean("blindTapEnabled", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 稳健模式（默认开）。关掉后不再限制候选的大小与位置，误触风险显著上升。 */
    private boolean isSafeMode() {
        try {
            JSONObject root = new JSONObject(RuleStore.getRawJson(this));
            JSONObject g = root.optJSONObject("global");
            return g == null || g.optBoolean("safeMode", true);
        } catch (Throwable t) {
            return true;
        }
    }

    /** 点击前是否复核目标仍在原位（默认开）。 */
    private boolean isVerifyBeforeTap() {
        try {
            JSONObject root = new JSONObject(RuleStore.getRawJson(this));
            JSONObject g = root.optJSONObject("global");
            return g == null || g.optBoolean("verifyBeforeTap", true);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 卡住自救（按返回键，默认开）。
     *
     * 只在「已确认是广告页」且「等够了 backKeyDelayMs 仍跳不掉」时才动手，
     * 所以默认值给的是开 —— 关掉它，用户就只能干等着广告自己结束，
     * 而「卡在开屏界面进不去也退不出」正是这么来的。
     */
    private boolean isBackKeyFallback() {
        try {
            JSONObject root = new JSONObject(RuleStore.getRawJson(this));
            JSONObject g = root.optJSONObject("global");
            return g == null || g.optBoolean("backKeyFallback", true);
        } catch (Throwable t) {
            return true;
        }
    }

    private void setGlobalBool(String key, boolean val) {
        try {
            JSONObject root = new JSONObject(RuleStore.getRawJson(this));
            JSONObject g = root.optJSONObject("global");
            if (g == null) {
                g = new JSONObject();
                root.put("global", g);
            }
            g.put(key, val);
            RuleStore.setRawJson(this, root.toString(2));
        } catch (Throwable t) {
            toast("修改失败：" + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ 小组件

    private interface OnChecked {
        void onChecked(boolean v);
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(rounded(C_CARD, 16));
        int p = dp(16);
        l.setPadding(p, p, p, p);
        l.setElevation(dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        l.setLayoutParams(lp);
        return l;
    }

    private TextView sectionTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(C_TEXT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        t.setLayoutParams(lp);
        return t;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0xFFEDF0F5);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(10);
        v.setLayoutParams(lp);
        return v;
    }

    private View switchRow(String title, String sub, boolean checked, final OnChecked cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        col.setLayoutParams(clp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(C_TEXT);
        col.addView(t);

        if (sub != null) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(12);
            s.setTextColor(C_SUB);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(2);
            s.setLayoutParams(slp);
            col.addView(s);
        }
        row.addView(col);

        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                cb.onChecked(v);
            }
        });
        row.addView(sw);
        return row;
    }

    private LinearLayout buttonRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        lp.bottomMargin = dp(8);
        r.setLayoutParams(lp);
        return r;
    }

    private Button primaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackground(rounded(C_PRIMARY, 12));
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    /** 描边按钮，用于次要但仍重要的操作。 */
    private Button outlineButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(C_PRIMARY);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(0x00000000);
        d.setCornerRadius(dp(12));
        d.setStroke(dp(1), 0xFFB9CCFF);
        b.setBackground(d);
        b.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private Button secondaryButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(C_PRIMARY);
        b.setBackground(rounded(0xFFEAF0FF, 12));
        b.setPadding(dp(10), dp(12), dp(10), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
